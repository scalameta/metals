package scalafmt.languageserver

import langserver.core.Connection
import langserver.messages.MessageType
import org.scalafmt.{Scalafmt, Formatted}
import org.scalafmt.config.Config
import scala.meta.AbsolutePath

class ScalafmtProvider(cwd: AbsolutePath, connection: Connection) {

  // TODO(gabro): the path should be configurable
  private def configFile = AbsolutePath(".scalafmt.conf")(cwd)

  def formatDocument(content: String): String = {
    (for {
      config <- Config.fromHoconFile(configFile.toFile).toEither.left.map(_.msg)
      formatted <- Scalafmt
        .format(content, config)
        .toEither
        .left
        .map(_.getMessage)
    } yield formatted) match {
      case Left(errorMessage) =>
        connection.showMessage(MessageType.Error, errorMessage)
        content
      case Right(formattedContent) => formattedContent
    }
  }

}
