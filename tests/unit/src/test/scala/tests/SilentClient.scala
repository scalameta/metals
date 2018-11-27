package tests
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsSlowTaskParams
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.MetalsStatusParams

class SilentClient extends MetalsLanguageClient {
  override def metalsStatus(params: MetalsStatusParams): Unit =
    ()
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] =
    CompletableFuture.completedFuture(null)
  override def telemetryEvent(`object`: Any): Unit =
    ()
  override def publishDiagnostics(diagnostics: PublishDiagnosticsParams): Unit =
    ()
  override def showMessage(messageParams: MessageParams): Unit =
    ()
  override def showMessageRequest(
      requestParams: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] =
    CompletableFuture.completedFuture(null)
  override def logMessage(message: MessageParams): Unit =
    ()
}
