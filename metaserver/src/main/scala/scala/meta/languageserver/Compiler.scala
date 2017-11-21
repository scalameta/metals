package scala.meta.languageserver

import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Response
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Connection
import langserver.messages.MessageType
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import org.langmeta.io.AbsolutePath
import org.langmeta.internal.semanticdb.schema.Document

class Compiler(
    serverConfig: ServerConfig,
    out: PrintStream,
    config: Observable[AbsolutePath],
    connection: Connection,
    buffers: Buffers
)(implicit s: Scheduler)
    extends LazyLogging {
  private implicit val cwd = serverConfig.cwd
  private val (documentSubscriber, myDocumentPublisher) =
    Observable.multicast[Document](MulticastStrategy.Publish)
  val documentPublisher: Observable[Document] = myDocumentPublisher
  private val indexedJars: ConcurrentHashMap[AbsolutePath, Unit] =
    new ConcurrentHashMap[AbsolutePath, Unit]()
  val onNewCompilerConfig: Observable[
    (Effects.InstallPresentationCompiler, Effects.IndexSourcesClasspath)
  ] =
    config
      .map(path => CompilerConfig.fromPath(path))
      .flatMap { config =>
        Observable.fromTask(
          Task(loadNewCompilerGlobals(config))
            .zip(Task(indexDependencyClasspath(config)))
        )
      }

  def autocomplete(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): List[(String, String)] = {
    logger.info(s"Completion request at $path:$line:$column")
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).fold(noCompletions) { compiler =>
      compiler.reporter.reset()
      val source = code.take(offset) + "_CURSOR_" + code.drop(offset)
      val unit = compiler.newCompilationUnit(source, path.toString())
      val richUnit = new compiler.RichCompilationUnit(unit.source)
      compiler.unitOfFile(richUnit.source.file) = richUnit
      val position = richUnit.position(offset)
      logger.info(s"Completion request at position $position")
      val results = compiler.completionsAt(position).matchingResults()
      results
        .map(r => (r.sym.signatureString, r.symNameDropLocal.decoded))
        .distinct
    }
  }

  def typeAt(path: AbsolutePath, line: Int, column: Int): Option[String] = {
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).flatMap { compiler =>
      compiler.reporter.reset()
      val unit = compiler.newCompilationUnit(code, path.toString())
      val richUnit = new compiler.RichCompilationUnit(unit.source)
      compiler.unitOfFile(richUnit.source.file) = richUnit
      val position = richUnit.position(offset)
      val response = ask[compiler.Tree](r => compiler.askTypeAt(position, r))
      val typedTree = response.get.swap
      typedTree.toOption.flatMap(t => typeOfTree(compiler)(t))
    }
  }

  private val compilerByPath = mutable.Map.empty[AbsolutePath, Global]
  private def loadNewCompilerGlobals(
      config: CompilerConfig
  ): Effects.InstallPresentationCompiler = {
    logger.info(s"Loading new compiler from config $config")
    val vd = new io.VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = config.classpath
    settings.processArgumentString(
      ("-Ypresentation-any-thread" :: config.scalacOptions).mkString(" ")
    )
    val compiler = new Global(settings, new StoreReporter)
    config.sources.foreach { path =>
      // TODO(olafur) garbage collect compilers from removed files.
      compilerByPath(path) = compiler
    }
    Effects.InstallPresentationCompiler
  }

  private def indexDependencyClasspath(
      config: CompilerConfig
  ): Effects.IndexSourcesClasspath = {
    if (!serverConfig.indexClasspath) return Effects.IndexSourcesClasspath
    val buf = List.newBuilder[AbsolutePath]
    val sourceJars = config.sourceJars
    sourceJars.foreach { jar =>
      // ensure we only index each jar once even under race conditions.
      indexedJars.computeIfAbsent(jar, _ => buf += jar)
    }
    val sourcesClasspath = buf.result()
    if (sourcesClasspath.nonEmpty) {
      logger.info(
        s"Indexing classpath with ${sourcesClasspath.length} entries..."
      )
    }
    val db = ctags.Ctags.indexDatabase(sourcesClasspath)
    db.documents.foreach(documentSubscriber.onNext)
    Effects.IndexSourcesClasspath
  }
  private def noCompletions: List[(String, String)] = {
    connection.showMessage(
      MessageType.Warning,
      "Run project/config:scalametaEnableCompletions to setup completion for this " +
        "config.in(project) or *:scalametaEnableCompletions for all projects/configurations"
    )
    Nil
  }
  private def lineColumnToOffset(
      contents: String,
      line: Int,
      column: Int
  ): Int = {
    var i = 0
    var l = line
    while (l > 0) {
      if (contents(i) == '\n') l -= 1
      i += 1
    }
    i + column
  }

  private def ask[A](f: Response[A] => Unit): Response[A] = {
    val r = new Response[A]
    f(r)
    r
  }

  private def typeOfTree(c: Global)(t: c.Tree): Option[String] = {
    import c._

    val refinedTree = t match {
      case t: ImplDef if t.impl != null => t.impl
      case t: ValOrDefDef if t.tpt != null => t.tpt
      case t: ValOrDefDef if t.rhs != null => t.rhs
      case x => x
    }

    Option(refinedTree.tpe).map(_.toLongString)
  }

}
