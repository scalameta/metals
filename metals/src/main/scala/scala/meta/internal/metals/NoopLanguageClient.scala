package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
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
object NoopLanguageClient extends MetalsLanguageClient {
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
  override def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult] = {
    CompletableFuture.completedFuture(MetalsInputBoxResult(cancelled = true))
  }
}
