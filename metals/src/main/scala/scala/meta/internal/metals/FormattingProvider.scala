package scala.meta.internal.metals

import scala.meta._
import org.eclipse.{lsp4j => l}
import MetalsEnrichments._
import scala.language.reflectiveCalls
import java.util
import java.nio.file.Files
import scala.util.Try

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
    (for {
      scalafmt <- classloadedScalafmt
      formatted <- scalafmtConfigPath match {
        case Some(configPath) =>
          scalafmt
            .format(input.text, configPath.toString, input.path)
            .fold(e => {
              scribe.error("Error while running Scalafmt", e)
              None
            }, Some(_))
        case None if !userConfig().scalafmtRequireConfigFile =>
          scalafmt
            .format(input.text, input.path)
            .fold(e => {
              scribe.error("Error while running Scalafmt", e)
              None
            }, Some(_))
        case _ =>
          scribe.info(
            "Skipping formatting request because no Scalafmt config file is present"
          )
          None
      }
    } yield {
      List(new l.TextEdit(fullDocumentRange, formatted))
    }).getOrElse(Nil).asJava
  }

  private def scalafmtConfigPath: Option[AbsolutePath] = {
    val relativePath =
      userConfig().scalafmtConfigPath.getOrElse(".scalafmt.conf")
    Some(workspace.resolve(relativePath))
      .filter(path => Files.isRegularFile(path.toNIO))
  }

  private val classloadedScalafmt: Option[Scalafmt] = {
    embedded.scalafmtJars match {
      case Some(classloader) =>
        type Scalafmt210 = {
          def format(code: String, configFile: String, filename: String): String
          def format(code: String, filename: String): String
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
          ): Try[String] =
            Try(scalafmt210.format(code, configFile, filename))
          def format(code: String, filename: String): Try[String] =
            Try(scalafmt210.format(code, filename))
        })
      case None =>
        scribe.error(
          "not found: scalafmt classloader. Make sure you have specified an existing version of Scalafmt in the settings"
        )
        None
    }
  }

}

private trait Scalafmt {
  def format(code: String, configFile: String, filename: String): Try[String]
  def format(code: String, filename: String): Try[String]
}
