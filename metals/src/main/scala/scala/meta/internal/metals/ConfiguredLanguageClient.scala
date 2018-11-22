package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams

/**
 * An implementation of MetalsLanguageClient that ignores custom Metals extension like `metals/status`.
 */
class ConfiguredLanguageClient(
    underlying: MetalsLanguageClient,
    config: MetalsServerConfig
) extends MetalsLanguageClient {
  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (config.statusBar.isOn) {
      underlying.metalsStatus(params)
    } else if (config.statusBar.isLogMessage) {
      if (params.text.nonEmpty) {
        logMessage(new MessageParams(MessageType.Info, params.text))
      }
    } else {
      ()
    }
  }
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] = {
    if (config.slowTask.isOn) {
      underlying.metalsSlowTask(params)
    } else {
      if (config.slowTask.isStatusBar) {
        metalsStatus(MetalsStatusParams(params.message))
      }
      new CompletableFuture[MetalsSlowTaskResult]()
    }
  }
  override def telemetryEvent(value: Any): Unit =
    underlying.telemetryEvent(value)
  override def publishDiagnostics(diagnostics: PublishDiagnosticsParams): Unit =
    underlying.publishDiagnostics(diagnostics)
  override def showMessage(params: MessageParams): Unit = {
    if (config.showMessage.isOn) {
      underlying.showMessage(params)
    } else if (config.showMessage.isLogMessage) {
      logMessage(params)
    }
  }
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    if (config.showMessageRequest.isOn) {
      underlying.showMessageRequest(params)
    } else {
      if (config.showMessageRequest.isLogMessage) {
        logMessage(params)
      }
      new CompletableFuture[MessageActionItem]()
    }
  }

  override def logMessage(message: MessageParams): Unit =
    underlying.logMessage(message)
}
