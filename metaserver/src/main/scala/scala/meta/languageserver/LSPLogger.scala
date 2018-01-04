package scala.meta.languageserver

import java.nio.charset.StandardCharsets.UTF_8
import scala.beans.BeanProperty
import org.langmeta.lsp.Window._
import org.langmeta.jsonrpc.JsonRpcClient
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

class LSPLogger(@BeanProperty var encoder: PatternLayoutEncoder)
    extends AppenderBase[ILoggingEvent] {
  import LSPLogger._

  override def append(event: ILoggingEvent): Unit = {
    val message =
      if (encoder != null) new String(encoder.encode(event), UTF_8)
      else event.getFormattedMessage
    notifications.foreach { implicit client =>
      logMessage.log(message)
    }
  }
}

object LSPLogger {
  var notifications: Option[JsonRpcClient] = None
}
