package scala.meta.languageserver

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.AppenderBase
import langserver.core.Connection
import langserver.types.MessageType
import java.nio.charset.StandardCharsets.UTF_8
import scala.beans.BeanProperty

class LSPLogger(@BeanProperty var encoder: PatternLayoutEncoder)
    extends AppenderBase[ILoggingEvent] {
  import LSPLogger._

  override def append(event: ILoggingEvent): Unit = {
    val message =
      if (encoder != null) new String(encoder.encode(event), UTF_8)
      else event.getFormattedMessage
    if (connection != null) {
      connection.logMessage(MessageType.Log, message)
    }
  }
}

object LSPLogger {
  var connection: Connection = null
}
