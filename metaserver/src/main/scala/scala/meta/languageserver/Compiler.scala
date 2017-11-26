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
import langserver.types.TextDocumentIdentifier
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

  def getCompiler(td: TextDocumentIdentifier): Option[Global] =
    Uri.toPath(td.uri).flatMap(getCompiler)
  def getCompiler(path: AbsolutePath): Option[Global] = {
    compilerByPath.get(path).map { compiler =>
      compiler.reporter.reset()
      compiler
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
}

object Compiler extends LazyLogging {

  def addCompilationUnit(
      global: Global,
      code: String,
      filename: String,
      cursor: Option[Int]
  ): global.RichCompilationUnit = {
    val codeWithCursor = cursor match {
      case Some(offset) =>
        code.take(offset) + "_CURSOR_" + code.drop(offset)
      case _ => code
    }
    val unit = global.newCompilationUnit(codeWithCursor, filename)
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
