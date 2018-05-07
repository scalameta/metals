package scala.meta.metals.compiler

import scala.collection.mutable
import scala.meta.metals.Effects
import scala.meta.metals.Uri
import org.langmeta.lsp.TextDocumentIdentifier
import org.langmeta.lsp.VersionedTextDocumentIdentifier
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Response
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.lsp.VersionedTextDocumentIdentifier
import org.langmeta.lsp.Window.showMessage
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath
import org.langmeta.jsonrpc.JsonRpcClient

/** Responsible for keeping fresh scalac global instances. */
class ScalacProvider()(implicit client: JsonRpcClient) extends LazyLogging {

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
      .orElse(compilerBySourceDirectory(uri).map {
        case (_, global) => global
      })
      .map { compiler =>
        compiler.reporter.reset()
        compiler
      }
  }

  def compilerBySourceDirectory(uri: Uri): Option[(CompilerConfig, Global)] = {
    val path = uri.toAbsolutePath.toNIO
    compilerByConfigOrigin.values.collectFirst {
      case (config, global)
          if config.sourceDirectories.exists(
            dir => path.startsWith(dir.toNIO)
          ) =>
        config -> global
    }
  }
  private val compilerByConfigOrigin =
    mutable.Map.empty[AbsolutePath, (CompilerConfig, Global)]
  private val compilerByPath = mutable.Map.empty[Uri, Global]
  def configForUri(uri: Uri): Option[CompilerConfig] =
    compilerBySourceDirectory(uri).map {
      case (config, _) => config
    }

  def allCompilerConfigs: Iterable[CompilerConfig] =
    compilerByConfigOrigin.values.map {
      case (config, _) => config
    }
  def loadNewCompilerGlobals(
      config: CompilerConfig
  ): Effects.InstallPresentationCompiler = {
    if (config.scalaVersion.major == 2 && config.scalaVersion.minor == 12) {
      logger.info(s"Loading new compiler from config $config")
      val compiler =
        ScalacProvider.newCompiler(config.classpath, config.scalacOptions)
      compilerByConfigOrigin(config.origin) = config -> compiler
      config.sources.foreach { path =>
        compilerByPath(Uri(path)) = compiler
      }
    } else {
      val scalaV = config.scalaVersion.unparse
      logger.warn(
        s"Unsupported scala version $scalaV. Skipping presentation compiler instantiation"
      )
      showMessage.warn(
        s"Unsupported scala version $scalaV. Completions and scalac diagnostics won't be available"
      )
    }
    Effects.InstallPresentationCompiler
  }

  def resetCompilers(): Unit =
    compilerByConfigOrigin.values.foreach {
      case (_, global) => global.askReset()
    }

}

object ScalacProvider {

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
