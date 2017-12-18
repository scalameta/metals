package scala.meta.languageserver.compiler

import scala.collection.mutable
import scala.meta.languageserver.Effects
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.Uri
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Response
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import langserver.types.TextDocumentIdentifier
import langserver.types.VersionedTextDocumentIdentifier
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath

/** Responsible for keeping fresh scalac global instances. */
class ScalacProvider(
    serverConfig: ServerConfig
) extends LazyLogging {

  def getCompiler(input: Input.VirtualFile): Option[Global] =
    getCompiler(Uri(input.path))

  def getCompiler(td: TextDocumentIdentifier): Option[Global] =
    getCompiler(Uri(td.uri))

  def getCompiler(td: VersionedTextDocumentIdentifier): Option[Global] =
    getCompiler(Uri(td.uri))

  def getCompiler(uri: Uri): Option[Global] = {
    compilerByPath
      .get(uri)
      // Looking up by sourceDirectory alone is not enough since
      // in sbt it's possible to `sources += file("blah")`, which would
      // not be respected if we only went by directories.
      .orElse(compilerBySourceDirectory(uri))
      .map { compiler =>
        compiler.reporter.reset()
        compiler
      }
  }

  def compilerBySourceDirectory(uri: Uri): Option[Global] = {
    val path = uri.toAbsolutePath.toNIO
    compilerByConfigOrigin.values.collectFirst {
      case (config, global)
          if config.sourceDirectories.exists(
            dir => path.startsWith(dir.toNIO)
          ) =>
        global
    }
  }
  private val compilerByConfigOrigin =
    mutable.Map.empty[AbsolutePath, (CompilerConfig, Global)]
  private val compilerByPath = mutable.Map.empty[Uri, Global]
  def loadNewCompilerGlobals(
      config: CompilerConfig
  ): Effects.InstallPresentationCompiler = {
    logger.info(s"Loading new compiler from config $config")
    val compiler =
      ScalacProvider.newCompiler(config.classpath, config.scalacOptions)
    compilerByConfigOrigin(config.origin) = config -> compiler
    config.sources.foreach { path =>
      compilerByPath(Uri(path)) = compiler
    }
    Effects.InstallPresentationCompiler
  }

  def resetCompilers(): Unit =
    compilerByConfigOrigin.values.foreach {
      case (_, global) => global.askReset()
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
    val options =
      "-Ypresentation-any-thread" ::
        scalacOptions.filterNot(_.contains("semanticdb"))
    val vd = new io.VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath
    if (classpath.isEmpty) {
      settings.usejavacp.value = true
    }
    settings.processArgumentString(options.mkString(" "))
    val compiler = new Global(settings, new StoreReporter)
    compiler
  }
  def ask[A](f: Response[A] => Unit): Response[A] = {
    val r = new Response[A]
    f(r)
    r
  }
}
