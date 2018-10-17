package scala.meta.internal.metals

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import scribe.Level
import scribe.LogRecord
import scribe.writer.Writer

object LanguageClientLogger extends Writer {
  var languageClient: Option[LanguageClient] = None
  override def write[M](record: LogRecord[M], output: String): Unit = {
    languageClient.foreach { client =>
      val messageType = record.level match {
        case Level.Error => MessageType.Error
        case Level.Warn => MessageType.Warning
        case Level.Info => MessageType.Info
        case _ => MessageType.Log
      }
      client.logMessage(new MessageParams(messageType, record.message))
    }
  }
}
