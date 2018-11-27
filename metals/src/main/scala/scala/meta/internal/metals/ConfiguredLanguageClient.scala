package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * Delegates requests/notifications to the underlying language client according to the user configuration.
 *
 * This wrapper class manages differences in how editors interpret LSP endpoints slightly differently,
 * especially the window/{logMessage,showMessage} notifications. For example, with vim-lsc the messages
 * from window/logMessage are always visible in the UI while in VS Code the logs are hidden by default.
 */
final class ConfiguredLanguageClient(
    underlying: MetalsLanguageClient,
    config: MetalsServerConfig
)(implicit ec: ExecutionContext)
    extends MetalsLanguageClient {

  override def registerCapability(
      params: RegistrationParams
  ): CompletableFuture[Void] = {
    underlying.registerCapability(params)
  }

  override def unregisterCapability(
      params: UnregistrationParams
  ): CompletableFuture[Void] = {
    underlying.unregisterCapability(params)
  }

  override def applyEdit(
      params: ApplyWorkspaceEditParams
  ): CompletableFuture[ApplyWorkspaceEditResponse] = {
    underlying.applyEdit(params)
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (config.statusBar.isOn) {
      underlying.metalsStatus(params)
    } else if (config.statusBar.isLogMessage && !pendingShowMessage.get()) {
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

  private val pendingShowMessage = new AtomicBoolean(false)
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    if (config.showMessageRequest.isOn) {
      pendingShowMessage.set(true)
      val result = underlying.showMessageRequest(params)
      result.asScala.onComplete(_ => pendingShowMessage.set(false))
      result
    } else {
      if (config.showMessageRequest.isLogMessage) {
        logMessage(params)
      }
      new CompletableFuture[MessageActionItem]()
    }
  }

  override def logMessage(message: MessageParams): Unit = {
    if (config.statusBar.isLogMessage && message.getType == MessageType.Log) {
      // window/logMessage is reserved for the status bar so we don't publish
      // scribe.{info,warn,error} logs here. Users should look at .metals/metals.log instead.
      ()
    } else {
      underlying.logMessage(message)
    }
  }
}
