package scala.meta.languageserver

import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.meta.languageserver.storage.LevelDBMap
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
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath
import org.langmeta.internal.semanticdb.schema.Document
import ScalametaLanguageServer.cacheDirectory
import scala.reflect.internal.util.Position
import langserver.types.SignatureHelp
import langserver.types.SymbolInformation
import Compiler.ask
import scala.collection.immutable
import langserver.types.ParameterInformation
import langserver.types.SignatureInformation

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
            .zip(Task(indexDependencyClasspath(config.sourceJars)))
        )
      }

  def signatureHelp(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): SignatureHelp = {
    getCompiler(path, line, column - 1).fold(noSignatureHelp) {
      case (compiler, position) =>
        logger.info(s"Signature help at $path:$line:$column")
        val signatureInformations = for {
          member <- compiler.completionsAt(position).matchingResults().distinct
          if member.sym.isMethod
        } yield {
          val sym = member.sym.asMethod
          val parameterInfos = sym.paramLists.headOption.map { params =>
            params.map { param =>
              ParameterInformation(
                label = param.nameString,
                documentation =
                  Some(s"${param.nameString}: ${param.info.toLongString}")
              )
            }
          }
          SignatureInformation(
            label = sym.nameString,
            documentation = Some(sym.info.toLongString),
            parameters = parameterInfos.getOrElse(Nil)
          )
        }
        SignatureHelp(signatureInformations, None, None)
    }
  }

  def autocomplete(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): List[(String, String)] = {
    logger.info(s"Completion request at $path:$line:$column")
    getCompiler(path, line, column).fold(noCompletions) {
      case (compiler, position) =>
        logger.info(s"Completion request at position $position")
        val results = compiler.completionsAt(position).matchingResults()
        results
          .map(r => (r.sym.signatureString, r.symNameDropLocal.decoded))
          .distinct
    }
  }

  def typeAt(path: AbsolutePath, line: Int, column: Int): Option[String] = {
    getCompiler(path, line, column).flatMap {
      case (compiler, position) =>
        val response = ask[compiler.Tree](r => compiler.askTypeAt(position, r))
        val typedTree = response.get.swap
        typedTree.toOption.flatMap(t => typeOfTree(compiler)(t))
    }
  }

  def getCompiler(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[(Global, Position)] = {
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).map { compiler =>
      compiler.reporter.reset()
      val richUnit =
        Compiler.addCompilationUnit(compiler, code, path.toString())
      val position = richUnit.position(offset)
      compiler -> position
    }
  }

  private val compilerByPath = mutable.Map.empty[AbsolutePath, Global]
  private def loadNewCompilerGlobals(
      config: CompilerConfig
  ): Effects.InstallPresentationCompiler = {
    logger.info(s"Loading new compiler from config $config")
    val compiler = Compiler.newCompiler(config.classpath, config.scalacOptions)
    config.sources.foreach { path =>
      // TODO(olafur) garbage collect compilers from removed files.
      compilerByPath(path) = compiler
    }
    Effects.InstallPresentationCompiler
  }

  // NOTE(olafur) this probably belongs somewhere else than Compiler, see
  // https://github.com/scalameta/language-server/issues/48
  def indexDependencyClasspath(
      sourceJars: List[AbsolutePath]
  ): Effects.IndexSourcesClasspath = {
    if (!serverConfig.indexClasspath) return Effects.IndexSourcesClasspath
    val sourceJarsWithJDK =
      if (serverConfig.indexJDK)
        CompilerConfig.jdkSources.fold(sourceJars)(_ :: sourceJars)
      else sourceJars
    val buf = List.newBuilder[AbsolutePath]
    sourceJarsWithJDK.foreach { jar =>
      // ensure we only index each jar once even under race conditions.
      // race conditions are not unlikely since multiple .compilerconfig
      // are typically created at the same time for each project/configuration
      // combination. Duplicate tasks are expensive, for example we don't want
      // to index the JDK twice on first startup.
      indexedJars.computeIfAbsent(jar, _ => buf += jar)
    }
    val sourceJarsToIndex = buf.result()
    // Acquire a lock on the leveldb cache only during indexing.
    LevelDBMap.withDB(cacheDirectory.resolve("leveldb").toFile) { db =>
      sourceJarsToIndex.foreach { path =>
        logger.info(s"Indexing classpath entry $path...")
        val database = db.getOrElseUpdate[AbsolutePath, Database](path, { () =>
          ctags.Ctags.indexDatabase(path :: Nil)
        })
        database.documents.foreach(documentSubscriber.onNext)
      }
    }
    Effects.IndexSourcesClasspath
  }

  private def noSignatureHelp: SignatureHelp = SignatureHelp(Nil, None, None)
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

object Compiler {
  def addCompilationUnit(
      global: Global,
      code: String,
      filename: String
  ): global.RichCompilationUnit = {
    val unit = global.newCompilationUnit(code, filename)
    val richUnit = new global.RichCompilationUnit(unit.source)
    global.unitOfFile(richUnit.source.file) = richUnit
    richUnit
  }
  def newCompiler(classpath: String, scalacOptions: List[String]): Global = {
    val vd = new io.VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath
    if (classpath.isEmpty) {
      settings.usejavacp.value = true
    }
    settings.processArgumentString(
      ("-Ypresentation-any-thread" :: scalacOptions).mkString(" ")
    )
    val compiler = new Global(settings, new StoreReporter)
    compiler
  }
  def ask[A](f: Response[A] => Unit): Response[A] = {
    val r = new Response[A]
    f(r)
    r
  }
}
