package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext

import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.config.StatusBarState

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

/**
 * Delegates requests/notifications to the underlying language client according to the user configuration.
 *
 * This wrapper class manages differences in how editors interpret LSP endpoints slightly differently,
 * especially the window/{logMessage,showMessage} notifications. For example, with vim-lsc the messages
 * from window/logMessage are always visible in the UI while in VS Code the logs are hidden by default.
 */
final class ConfiguredLanguageClient(
    initial: MetalsLanguageClient,
    clientConfig: ClientConfiguration
)(implicit ec: ExecutionContext)
    extends DelegatingLanguageClient(initial) {

  override def shutdown(): Unit = {
    underlying = NoopLanguageClient
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (clientConfig.statusBarState == StatusBarState.On) {
      underlying.metalsStatus(params)
    } else if (params.text.nonEmpty && !pendingShowMessage.get()) {
      if (clientConfig.statusBarState == StatusBarState.ShowMessage) {
        underlying.showMessage(new MessageParams(MessageType.Log, params.text))
      } else if (clientConfig.statusBarState == StatusBarState.LogMessage) {
        underlying.logMessage(new MessageParams(MessageType.Log, params.text))
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
    if (clientConfig.slowTaskIsOn) {
      underlying.metalsSlowTask(params)
    } else {
      new CompletableFuture[MetalsSlowTaskResult]()
    }
  }
  override def showMessage(params: MessageParams): Unit = {
    underlying.showMessage(params)
  }

  private val pendingShowMessage = new AtomicBoolean(false)
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    pendingShowMessage.set(true)
    val result = underlying.showMessageRequest(params)
    result.asScala.onComplete(_ => pendingShowMessage.set(false))
    result
  }

  override def logMessage(message: MessageParams): Unit = {
    if (
      clientConfig.statusBarState == StatusBarState.LogMessage && message.getType == MessageType.Log
    ) {
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
    if (clientConfig.isExecuteClientCommandProvider) {
      params.getCommand match {
        case ClientCommands.RefreshModel()
            if !clientConfig.isDebuggingProvider =>
          () // ignore
        case _ =>
          underlying.metalsExecuteClientCommand(params)
      }
    }
  }

  override def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult] = {
    if (clientConfig.isInputBoxEnabled) {
      underlying.metalsInputBox(params)
    } else {
      CompletableFuture.completedFuture(MetalsInputBoxResult(cancelled = true))
    }
  }

  override def metalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[MetalsQuickPickResult] = {
    if (clientConfig.isQuickPickProvider) {
      underlying.metalsQuickPick(params)
    } else {
      showMessageRequest(
        toShowMessageRequestParams(params)
      ).asScala
        .map(item => MetalsQuickPickResult(itemId = item.getTitle()))
        .asJava
    }
  }

  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = {
    if (clientConfig.isDecorationProvider) {
      underlying.metalsPublishDecorations(params)
    }
  }

  private def toShowMessageRequestParams(
      params: MetalsQuickPickParams
  ): ShowMessageRequestParams = {
    val result = new ShowMessageRequestParams()
    result.setMessage(params.placeHolder)
    result.setActions(params.items.map(item => new MessageActionItem(item.id)))
    result
  }

}
