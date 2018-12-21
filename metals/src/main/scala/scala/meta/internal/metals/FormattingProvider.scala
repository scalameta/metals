package scala.meta.internal.metals

import scala.meta._
import org.eclipse.{lsp4j => l}
import MetalsEnrichments._
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import scala.language.reflectiveCalls
import java.util
import java.nio.file.Files
import scala.util.Try
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import com.typesafe.config.ConfigFactory
import java.nio.charset.StandardCharsets
import java.util.Collections
import org.eclipse.{lsp4j => l}
import org.eclipse.lsp4j.PublishDiagnosticsParams
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.ScalafmtError
import scala.util.control.NonFatal

/**
 * Implement text formatting using Scalafmt
 */
final class FormattingProvider(
    workspace: AbsolutePath,
    buffers: Buffers,
    embedded: Embedded,
    userConfig: () => UserConfiguration,
    client: MetalsLanguageClient,
    statusBar: StatusBar,
    icons: Icons
)(implicit ec: ExecutionContext) {

  private val defaultScalafmtVersion = "1.5.1"

  def format(path: AbsolutePath): util.List[l.TextEdit] = {
    val input = path.toInputFromBuffers(buffers)
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLSP
    val scalafmtConf = workspace.resolve(userConfig().scalafmtConfigPath)
    val edits = for {
      confPath <- {
        if (scalafmtConf.isFile) {
          Some(scalafmtConf)
        } else {
          handleMissingFile(scalafmtConf)
          None
        }
      }
      config <- parseConfig(confPath)
      version = {
        if (config.hasPath("version")) config.getString("version")
        else defaultScalafmtVersion
      }
      (includeFilters, excludeFilters) = {
        val excludeFilters =
          if (config.hasPath("project.excludeFilters"))
            config.getStringList("project.excludeFilters").asScala
          else Nil
        val includeFilters =
          if (config.hasPath("project.includeFilters"))
            config.getStringList("project.includeFilters").asScala
          else List(".*\\.scala$", ".*\\.sbt$", ".*\\.sc$")
        (includeFilters, excludeFilters)
      }
      if FilterMatcher(includeFilters, excludeFilters).matches(path.toString)
      scalafmt <- classloadScalafmt(version)
      formatted <- scalafmt
        .format(input.text, scalafmtConf.toString(), input.path)
        .fold(
          e => {
            scribe.error(s"scalafmt error: ${e.getMessage}")
            client.showMessage(ScalafmtError.formatError(e))
            None
          },
          Some(_)
        )
    } yield List(new l.TextEdit(fullDocumentRange, formatted))
    edits.getOrElse(Nil).asJava
  }

  def parseConfig(path: AbsolutePath): Option[Config] = {
    try {
      val result = ConfigFactory.parseFile(path.toFile)
      client.publishDiagnostics(
        new PublishDiagnosticsParams(
          path.toURI.toString,
          Collections.emptyList()
        )
      )
      Some(result)
    } catch {
      case e: ConfigException =>
        handleConfigError(path, e)
        None
    }
  }

  def handleConfigError(path: AbsolutePath, e: ConfigException): Unit = {
    val message = e match {
      case e: ConfigException.Parse if e.origin().lineNumber() >= 0 =>
        val line = e.origin().lineNumber() - 1
        val message = e.getMessage.stripPrefix(s"$path: ${line + 1}: ")
        val diagnostic = new l.Diagnostic(
          new l.Range(
            new l.Position(line, 0),
            new l.Position(line, 0)
          ),
          message,
          l.DiagnosticSeverity.Error,
          "hocon"
        )
        client.publishDiagnostics(
          new PublishDiagnosticsParams(
            path.toURI.toString,
            Collections.singletonList(diagnostic)
          )
        )
        statusBar.addMessage(
          MetalsStatusParams(
            s"${icons.alert}.scalafmt.conf parse error",
            command = ClientCommands.FocusDiagnostics.id
          )
        )
        message
      case _ =>
        e.getMessage
    }
    val params = ScalafmtError.configParseError(
      path.toRelative(workspace),
      message
    )
    client.showMessage(params)
  }

  def handleMissingFile(path: AbsolutePath): Unit = {
    val params = MissingScalafmtConf.params(path)
    client.showMessageRequest(params).asScala.foreach { item =>
      if (item == MissingScalafmtConf.createFile) {
        val text =
          s"""version = "$defaultScalafmtVersion"
             |""".stripMargin

        Files.createDirectories(path.toNIO.getParent)
        Files.write(path.toNIO, text.getBytes(StandardCharsets.UTF_8))
        client.showMessage(MissingScalafmtConf.fixedParams)
      }
    }
  }

  private def classloadScalafmt(version: String): Option[Scalafmt] = {
    embedded.scalafmtJars(version) match {
      case Some(classloader) =>
        type Scalafmt210 = {
          def format(code: String, configFile: String, filename: String): String
        }
        val scalafmt210 = classloader
          .loadClass("org.scalafmt.cli.Scalafmt210")
          .newInstance()
          .asInstanceOf[Scalafmt210]
        // See https://github.com/scalameta/scalameta/issues/1068
        def clearMegaCache(): Unit = {
          try {
            val cls =
              classloader.loadClass(PlatformTokenizerCache.getClass.getName)
            val module = cls.getField("MODULE$")
            module.setAccessible(true)
            val megacache = cls.getMethod("megaCache")
            megacache.setAccessible(true)
            val instance = module.get(null)
            val map =
              megacache.invoke(instance).asInstanceOf[java.util.Map[_, _]]
            map.clear()
          } catch {
            case NonFatal(e) =>
              scribe.error("megaCache error", e)
          }
        }
        Some(new Scalafmt {
          def format(
              code: String,
              configFile: String,
              filename: String
          ): Try[String] = {
            val r = Try(scalafmt210.format(code, configFile, filename))
            clearMegaCache()
            r
          }
        })
      case None =>
        val params =
          ScalafmtError.downloadError(version)
        client.showMessage(params)
        None
    }
  }

}

private trait Scalafmt {
  def format(code: String, configFile: String, filename: String): Try[String]
}
