package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import scala.meta.internal.tvp._
import scala.meta.internal.decorations.PublishDecorationsParams

class DelegatingLanguageClient(var underlying: MetalsLanguageClient)
    extends MetalsLanguageClient {

  override def shutdown(): Unit = {
    underlying.shutdown()
  }

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

  def configure(capabilities: ClientExperimentalCapabilities): Unit = {
    underlying match {
      case client: ConfiguredLanguageClient =>
        client.configure(capabilities)
      case _ =>
    }
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    underlying.metalsStatus(params)
  }

  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] = {
    underlying.metalsSlowTask(params)
  }

  override def telemetryEvent(value: Any): Unit = {
    underlying.telemetryEvent(value)
  }

  override def publishDiagnostics(
      diagnostics: PublishDiagnosticsParams
  ): Unit = {
    underlying.publishDiagnostics(diagnostics)
  }

  override def showMessage(params: MessageParams): Unit = {
    underlying.showMessage(params)
  }

  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    underlying.showMessageRequest(params)
  }

  override def logMessage(message: MessageParams): Unit = {
    underlying.logMessage(message)
  }

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    underlying.metalsExecuteClientCommand(params)
  }

  override def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult] = {
    underlying.metalsInputBox(params)
  }

  override def metalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[MetalsQuickPickResult] = {
    underlying.metalsQuickPick(params)
  }

  override def metalsTreeViewDidChange(
      params: TreeViewDidChangeParams
  ): Unit = {
    underlying.metalsTreeViewDidChange(params)
  }

  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = {
    underlying.metalsPublishDecorations(params)
  }

}
