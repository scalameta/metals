package scala.meta.languageserver.compiler

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.meta.languageserver.Effects
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.Uri
import scala.meta.languageserver.ctags
import scala.meta.languageserver.search.IndexDependencyClasspath
import scala.meta.languageserver.storage.LevelDBMap
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Response
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import langserver.types.TextDocumentIdentifier
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.internal.semanticdb.schema.Document
import org.langmeta.io.AbsolutePath

/** Responsible for keeping fresh scalac global instances. */
class ScalacProvider(
    serverConfig: ServerConfig,
    config: Observable[AbsolutePath]
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
            .zip(
              Task(
                IndexDependencyClasspath.apply(
                  serverConfig,
                  indexedJars,
                  documentSubscriber,
                  logger,
                  config.sourceJars
                )
              )
            )
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
    val compiler =
      ScalacProvider.newCompiler(config.classpath, config.scalacOptions)
    config.sources.foreach { path =>
      // TODO(olafur) garbage collect compilers from removed files.
      compilerByPath(path) = compiler
    }
    Effects.InstallPresentationCompiler
  }

}

object ScalacProvider extends LazyLogging {

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
