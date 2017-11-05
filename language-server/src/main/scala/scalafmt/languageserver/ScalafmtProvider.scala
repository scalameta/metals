package scalafmt.languageserver

import langserver.core.Connection
import langserver.messages.MessageType
import org.scalafmt.{Scalafmt, Formatted}
import org.scalafmt.config.Config
import scala.meta.AbsolutePath

class ScalafmtProvider(cwd: AbsolutePath, connection: Connection) {

  def formatDocument(content: String): String = {
    (for {
      config <- Config.fromHoconFile(cwd.toFile).toEither.left.map(_.msg)
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
