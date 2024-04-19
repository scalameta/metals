package scala.meta.metals.lsp

import org.eclipse.lsp4j.WorkDoneProgressCancelParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

trait WindowService {
  @JsonNotification("window/workDoneProgress/cancel")
  def didCancelWorkDoneProgress(params: WorkDoneProgressCancelParams): Unit
}
