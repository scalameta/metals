package scala.meta.internal.metals.clients.language

import java.util.concurrent.CompletableFuture

import scala.meta.internal.tvp._

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams

/**
 * A language client that ignores all requests.
 *
 * Used during shutdown procedure to ensure no status bar updates
 * or log messages are published during shutdown.
 */
abstract class NoopLanguageClient extends MetalsLanguageClient {

  override def showMessageRequest(
      params: ShowMessageRequestParams,
      defaultTo: () => MessageActionItem,
  ): CompletableFuture[MessageActionItem] = {
    showMessageRequest(params)
  }
  override def metalsStatus(params: MetalsStatusParams): Unit = ()
  override def telemetryEvent(`object`: Any): Unit = ()
  override def publishDiagnostics(diagnostics: PublishDiagnosticsParams): Unit =
    ()
  override def showMessage(messageParams: MessageParams): Unit = ()
  override def showMessageRequest(
      requestParams: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] =
    new CompletableFuture[MessageActionItem]()
  override def logMessage(message: MessageParams): Unit = ()
  override def metalsExecuteClientCommand(params: ExecuteCommandParams): Unit =
    ()
  override def rawMetalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[RawMetalsInputBoxResult] = {
    CompletableFuture.completedFuture(RawMetalsInputBoxResult(cancelled = true))
  }
  override def rawMetalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[RawMetalsQuickPickResult] = {
    CompletableFuture.completedFuture(
      RawMetalsQuickPickResult(cancelled = true)
    )
  }
  override def metalsTreeViewDidChange(
      params: TreeViewDidChangeParams
  ): Unit = ()

  override def refreshModel(): CompletableFuture[Unit] =
    CompletableFuture.completedFuture(())

  override def refreshCodeLenses(): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def refreshSemanticTokens(): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def createProgress(
      params: WorkDoneProgressCreateParams
  ): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def notifyProgress(params: ProgressParams): Unit = ()

  override def refreshInlayHints(): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }
}

object NoopLanguageClient extends NoopLanguageClient {}
