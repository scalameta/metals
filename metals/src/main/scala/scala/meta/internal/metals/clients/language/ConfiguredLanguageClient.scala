package scala.meta.internal.metals.clients.language

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext

import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.WorkspaceLspService
import scala.meta.internal.metals.config.StatusBarState
import scala.meta.internal.metals.config.StatusBarState.LogMessage
import scala.meta.internal.metals.config.StatusBarState.On
import scala.meta.internal.metals.config.StatusBarState.ShowMessage

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams

/**
 * Delegates requests/notifications to the underlying language client according to the user configuration.
 *
 * This wrapper class manages differences in how editors interpret LSP endpoints slightly differently,
 * especially the window/{logMessage,showMessage} notifications. For example, with vim-lsc the messages
 * from window/logMessage are always visible in the UI while in VS Code the logs are hidden by default.
 */
final class ConfiguredLanguageClient(
    initial: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    service: WorkspaceLspService,
)(implicit ec: ExecutionContext)
    extends DelegatingLanguageClient(initial) {

  override def shutdown(): Unit = {
    underlying = NoopLanguageClient
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    val level =
      params.level match {
        case "error" => MessageType.Error
        case "warn" => MessageType.Warning
        case _ => MessageType.Info
      }
    val statusBarState =
      params.getStatusType match {
        case StatusType.bsp => clientConfig.bspStatusBarState()
        case _ => clientConfig.statusBarState()
      }

    val logMessage = params.logMessage(clientConfig.icons())
    statusBarState match {
      case On => underlying.metalsStatus(params)
      case ShowMessage if logMessage.nonEmpty && !pendingShowMessage.get() =>
        if (params.command != null && params.command.nonEmpty) {
          val action = new MessageActionItem(
            Option(params.commandTooltip)
              .filter(_.nonEmpty)
              .getOrElse(params.command)
          )
          val requestParams = new ShowMessageRequestParams()
          requestParams.setMessage(logMessage)
          requestParams.setType(level)
          requestParams.setActions(List(action).asJava)
          underlying.showMessageRequest(requestParams).asScala.map {
            case `action` =>
              val execCommandParams =
                new ExecuteCommandParams(params.command, List.empty.asJava)
              if (ServerCommands.allIds.contains(params.command)) {
                service.executeCommand(execCommandParams)
              } else {
                underlying.metalsExecuteClientCommand(execCommandParams)
              }
            case _ =>
          }
        } else {
          underlying.showMessage(new MessageParams(level, logMessage))
        }
      case LogMessage if logMessage.nonEmpty =>
        underlying.logMessage(new MessageParams(level, logMessage))
      case _ =>
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
      clientConfig
        .statusBarState() == StatusBarState.LogMessage && message.getType == MessageType.Log
    ) {
      // window/logMessage is reserved for the status bar so we don't publish
      // scribe.{info,warn,error} logs here. Users should look at .metals/metals.log instead.
      ()
    } else {
      underlying.logMessage(message)
    }
  }

  override def refreshModel(): CompletableFuture[Unit] = {
    if (clientConfig.codeLenseRefreshSupport())
      underlying.refreshCodeLenses.thenApply(_ => ())
    else if (
      clientConfig.isExecuteClientCommandProvider() &&
      (clientConfig.isDebuggingProvider() || clientConfig.isRunProvider())
    ) {
      val params = ClientCommands.RefreshModel.toExecuteCommandParams()
      CompletableFuture.completedFuture(metalsExecuteClientCommand(params))
    } else CompletableFuture.completedFuture(())
  }

  override def refreshSemanticTokens(): CompletableFuture[Void] = {
    if (clientConfig.semanticTokensRefreshSupport()) {
      underlying
        .refreshSemanticTokens()
        .handle { (msg, ex) =>
          if (ex != null)
            scribe.warn(s"Error while refreshing semantic tokens: $msg", ex)
          msg
        }
    } else CompletableFuture.allOf()
  }

  override def refreshInlayHints(): CompletableFuture[Void] = {
    if (clientConfig.isInlayHintsEnabled()) {
      underlying
        .refreshInlayHints()
        .handle { (msg, ex) =>
          if (ex != null)
            scribe.warn(s"Error while refreshing inlayHints: $msg", ex)
          msg
        }
    } else CompletableFuture.allOf()
  }

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit =
    underlying.metalsExecuteClientCommand(params)

  override def rawMetalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[RawMetalsInputBoxResult] = {
    if (clientConfig.isInputBoxEnabled()) {
      underlying.rawMetalsInputBox(params)
    } else {
      CompletableFuture.completedFuture(
        RawMetalsInputBoxResult(cancelled = true)
      )
    }
  }

  override def rawMetalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[RawMetalsQuickPickResult] = {
    if (clientConfig.isQuickPickProvider()) {
      underlying.rawMetalsQuickPick(params)
    } else {
      showMessageRequest(
        toShowMessageRequestParams(params)
      ).asScala.map { itemOrNull =>
        Option(itemOrNull) match {
          case Some(item) => RawMetalsQuickPickResult(itemId = item.getTitle)
          case None => RawMetalsQuickPickResult(cancelled = true)
        }
      }.asJava
    }
  }

  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = {
    if (clientConfig.isDecorationProvider()) {
      underlying.metalsPublishDecorations(params)
    }
  }

  private def toShowMessageRequestParams(
      params: MetalsQuickPickParams
  ): ShowMessageRequestParams = {
    val result = new ShowMessageRequestParams()
    result.setMessage(params.placeHolder)
    result.setActions(params.items.map(item => new MessageActionItem(item.id)))
    result.setType(MessageType.Info)
    result
  }

  override def createProgress(
      params: WorkDoneProgressCreateParams
  ): CompletableFuture[Void] =
    if (clientConfig.hasWorkDoneProgressCapability()) {
      underlying.createProgress(params)
    } else CompletableFuture.completedFuture(null)

  override def notifyProgress(params: ProgressParams): Unit =
    if (clientConfig.hasWorkDoneProgressCapability()) {
      underlying.notifyProgress(params)
    }

}
