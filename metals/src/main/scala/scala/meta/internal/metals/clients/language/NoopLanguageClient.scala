package scala.meta.internal.metals.clients.language

import java.util.concurrent.CompletableFuture

import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.tvp._

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams

/**
 * A language client that ignores all requests.
 *
 * Used during shutdown procedure to ensure no status bar updates
 * or log messages are published during shutdown.
 */
abstract class NoopLanguageClient extends MetalsLanguageClient {
  override def metalsStatus(params: MetalsStatusParams): Unit = ()
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] =
    new CompletableFuture[MetalsSlowTaskResult]()
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
  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = ()

  override def refreshModel(): CompletableFuture[Unit] =
    CompletableFuture.completedFuture(())
}

object NoopLanguageClient extends NoopLanguageClient
