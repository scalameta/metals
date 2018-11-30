package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import javax.annotation.Nullable
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient

trait MetalsLanguageClient extends LanguageClient {

  /**
   * Display message in the editor "status bar", which should be displayed somewhere alongside the buffer.
   *
   * The status bar should always be visible to the user.
   *
   * - VS Code: https://code.visualstudio.com/docs/extensionAPI/vscode-api#StatusBarItem
   */
  @JsonNotification("metals/status")
  def metalsStatus(params: MetalsStatusParams): Unit

  /**
   * Starts a long running task with no estimate for how long it will take to complete.
   *
   * - request cancellation from the server indicates that the task has completed
   * - response with cancel=true indicates the client wishes to cancel the slow task
   */
  @JsonRequest("metals/slowTask")
  def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult]

  def shutdown(): Unit = {}

}

/**
 * Arguments for the metals/status notification.
 *
 * @param text The text to display in the status bar.
 * @param show if true, show the status bar.
 * @param hide if true, hide the status bar.
 * @param tooltip optional display this message when the user hovers over the status bar item.
 * @param command optional command that the client should trigger when the user clicks on
 *                the status bar item.
 */
case class MetalsStatusParams(
    text: String,
    @Nullable show: java.lang.Boolean = null,
    @Nullable hide: java.lang.Boolean = null,
    @Nullable tooltip: String = null,
    @Nullable command: String = null
)

case class MetalsSlowTaskParams(message: String)
case class MetalsSlowTaskResult(cancel: Boolean)
