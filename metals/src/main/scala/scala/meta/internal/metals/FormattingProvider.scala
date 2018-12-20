package scala.meta.internal.metals

import scala.meta._
import org.eclipse.{lsp4j => l}
import MetalsEnrichments._
import scala.language.reflectiveCalls
import java.util
import java.nio.file.Files
import scala.util.Try
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import com.typesafe.config.ConfigFactory

/**
 * Implement text formatting using Scalafmt
 */
final class FormattingProvider(
    workspace: AbsolutePath,
    buffers: Buffers,
    embedded: Embedded,
    userConfig: () => UserConfiguration
) {

  def format(path: AbsolutePath): util.List[l.TextEdit] = {
    val input = path.toInputFromBuffers(buffers)
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLSP
    val defaultScalafmtVersion = "1.5.1"
    (for {
      scalafmt <- scalafmtConfigPath match {
        case Some(configPath) =>
          val scalafmtConf = ConfigFactory.parseFile(configPath.toNIO.toFile)
          val scalafmtVersion = Try(scalafmtConf.getString("version")).toOption
            .getOrElse(defaultScalafmtVersion)
          classloadScalafmt(scalafmtVersion)
        case _ =>
          scribe.info(
            "Skipping formatting request because no Scalafmt config file is present"
          )
          None
      }
      configPath <- scalafmtConfigPath
      formatted <- scalafmt
        .format(input.text, configPath.toString, input.path)
        .fold(e => {
          scribe.error(s"Scalafmt error: ${e.getMessage}")
          None
        }, Some(_))
    } yield {
      List(new l.TextEdit(fullDocumentRange, formatted))
    }).getOrElse(Nil).asJava
  }

  private def scalafmtConfigPath: Option[AbsolutePath] = {
    Some(workspace.resolve(userConfig().scalafmtConfigPath))
      .filter(path => Files.isRegularFile(path.toNIO))
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
        Some(new Scalafmt {
          def format(
              code: String,
              configFile: String,
              filename: String
          ): Try[String] = {
            val r = Try(scalafmt210.format(code, configFile, filename))
            // See https://github.com/scalameta/scalameta/issues/1068
            PlatformTokenizerCache.megaCache.clear()
            r
          }
        })
      case None =>
        scribe.error(
          "not found: Scalafmt classloader. Make sure you have specified an existing version of Scalafmt in the settings"
        )
        None
    }
  }

}

private trait Scalafmt {
  def format(code: String, configFile: String, filename: String): Try[String]
}
