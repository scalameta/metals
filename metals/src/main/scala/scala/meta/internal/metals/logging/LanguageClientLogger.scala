package scala.meta.internal.metals.logging

import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import scribe.LogRecord
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer

/**
 * Scribe logging handler that forwards logging messages to the LSP editor client.
 */
object LanguageClientLogger extends Writer {
  var languageClient: Option[MetalsLanguageClient] = None
  override def write(
      record: LogRecord,
      output: LogOutput,
      outputFormat: OutputFormat,
  ): Unit = {
    MetalsSingletonLogFilter(record) match {
      // This filter should already be applied, but the filter is seemingly
      // only applied to the metals.log file writer, not the LSP client, so
      // we redo the filtering here.
      case None => return
      case _ =>
    }
    languageClient.foreach { client =>
      client.logMessage(new MessageParams(MessageType.Log, output.plainText))
    }
  }
}
