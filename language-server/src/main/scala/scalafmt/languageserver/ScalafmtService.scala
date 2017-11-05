package scalafmt.languageserver

import langserver.core.Connection
import langserver.messages.MessageType
import org.scalafmt.{Scalafmt, Formatted}
import scala.meta.AbsolutePath

class ScalafmtService(connection: Connection) {

  def formatDocument(content: String): String = {
    Scalafmt.format(content) match {
      case Formatted.Success(d) => d
      case Formatted.Failure(e) =>
        connection.showMessage(MessageType.Error, e.getMessage)
        content
    }
  }

}
