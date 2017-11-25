package scala.meta.languageserver

import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.meta.languageserver.Compiler.ask
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.compiler.CompletionProvider
import scala.meta.languageserver.compiler.SignatureHelpProvider
import scala.meta.languageserver.compiler.HoverProvider
import scala.meta.languageserver.storage.LevelDBMap
import scala.reflect.internal.util.Position
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Response
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Connection
import langserver.messages.CompletionList
import langserver.messages.MessageType
import langserver.messages.Hover
import langserver.types.SignatureHelp
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.internal.semanticdb.schema.Document
import org.langmeta.io.AbsolutePath

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
  val completionProvider = new CompletionProvider(connection)

  def signatureHelp(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): SignatureHelp = {
    logger.info(s"Signature help at $path:$line:$column")
    getCompiler(path, line, column - 1).fold(SignatureHelpProvider.empty) {
      case (compiler, position) =>
        SignatureHelpProvider.signatureHelp(compiler, position)
    }
  }

  def autocomplete(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): CompletionList = {
    logger.info(s"Completion request at $path:$line:$column")
    getCompiler(path, line, column).fold(completionProvider.empty) {
      case (compiler, position) =>
        completionProvider.completions(compiler, position)
    }
  }

  def hover(path: AbsolutePath, line: Int, column: Int): Hover = {
    logger.info(s"Hover at $path:$line:$column")
    getCompiler(path, line, column).fold(HoverProvider.empty) {
      case (compiler, position) =>
        HoverProvider.hover(compiler, position)
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

}

object Compiler extends LazyLogging {
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
