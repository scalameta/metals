package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

/**
 * An implementation of MetalsLanguageClient that ignores custom Metals extension like `metals/status`.
 */
class NoExtensionLanguageClient(
    underlying: LanguageClient,
    config: MetalsServerConfig
) extends MetalsLanguageClient {
  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (config.isLogStatusBar && params.text.nonEmpty) {
      logMessage(new MessageParams(MessageType.Info, params.text))
    }
  }
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] =
    new CompletableFuture[MetalsSlowTaskResult]()
  override def telemetryEvent(value: Any): Unit =
    underlying.telemetryEvent(value)
  override def publishDiagnostics(diagnostics: PublishDiagnosticsParams): Unit =
    underlying.publishDiagnostics(diagnostics)
  override def showMessage(params: MessageParams): Unit = {
    if (config.isLogShowMessage) {
      logMessage(params)
    } else {
      underlying.showMessage(params)
    }
  }
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    if (config.isLogShowMessageRequest) {
      logMessage(params)
      new CompletableFuture[MessageActionItem]()
    } else {
      underlying.showMessageRequest(params)
    }
  }
  override def logMessage(message: MessageParams): Unit =
    underlying.logMessage(message)
}
