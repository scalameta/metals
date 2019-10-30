package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
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
    initial: MetalsLanguageClient,
    config: MetalsServerConfig
)(implicit ec: ExecutionContext)
    extends DelegatingLanguageClient(initial) {

  @volatile private var debuggingSupported = true

  def configure(capabilities: ClientExperimentalCapabilities): Unit = {
    debuggingSupported = capabilities.debuggingProvider
  }

  override def shutdown(): Unit = {
    underlying = NoopLanguageClient
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (config.statusBar.isOn) {
      underlying.metalsStatus(params)
    } else if (params.text.nonEmpty && !pendingShowMessage.get()) {
      if (config.statusBar.isLogMessage) {
        underlying.logMessage(new MessageParams(MessageType.Log, params.text))
      } else if (config.statusBar.isShowMessage) {
        underlying.showMessage(new MessageParams(MessageType.Log, params.text))
      } else {
        ()
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

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    if (config.executeClientCommand.isOn) {
      params.getCommand match {
        case _ if config.executeClientCommand.isOff =>
        // ignore
        case ClientCommands.RefreshModel() if !debuggingSupported =>
        // ignore
        case _ =>
          underlying.metalsExecuteClientCommand(params)
      }
    }
  }

  override def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult] = {
    if (config.isInputBoxEnabled) {
      underlying.metalsInputBox(params)
    } else {
      CompletableFuture.completedFuture(MetalsInputBoxResult(cancelled = true))
    }
  }

}
