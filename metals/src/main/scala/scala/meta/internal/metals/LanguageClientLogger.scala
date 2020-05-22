package scala.meta.internal.metals

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import scribe.LogRecord
import scribe.output.LogOutput
import scribe.writer.Writer

/**
 * Scribe logging handler that forwards logging messages to the LSP editor client.
 */
object LanguageClientLogger extends Writer {
  var languageClient: Option[MetalsLanguageClient] = None
  override def write[M](record: LogRecord[M], output: LogOutput): Unit = {
    languageClient.foreach { client =>
      client.logMessage(new MessageParams(MessageType.Log, output.plainText))
    }
  }
}
