package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import javax.annotation.Nullable

import scala.meta.internal.decorations.DecorationClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.tvp._

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient

trait MetalsLanguageClient
    extends LanguageClient
    with TreeViewClient
    with DecorationClient {

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

  @JsonNotification("metals/executeClientCommand")
  def metalsExecuteClientCommand(params: ExecuteCommandParams): Unit

  final def refreshModel(): Unit = {
    val command = ClientCommands.RefreshModel.id
    val params = new ExecuteCommandParams(command, Nil.asJava)
    metalsExecuteClientCommand(params)
  }

  /**
   * Opens an input box to ask the user for input.
   *
   * @return the user provided input. The future can be cancelled, meaning
   *         the input box should be dismissed in the editor.
   */
  @JsonRequest("metals/inputBox")
  def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult]

  /**
   * Opens an menu to ask the user to pick one of the suggested options.
   *
   * @return the user provided pick. The future can be cancelled, meaning
   *         the input box should be dismissed in the editor.
   */
  @JsonRequest("metals/quickPick")
  def metalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[MetalsQuickPickResult]

  final def showMessage(messageType: MessageType, message: String): Unit = {
    val params = new MessageParams(messageType, message)
    showMessage(params)
  }

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

case class MetalsSlowTaskParams(
    message: String,
    quietLogs: java.lang.Boolean = null,
    secondsElapsed: java.lang.Integer = null
)
case class MetalsSlowTaskResult(cancel: Boolean)

case class MetalsInputBoxParams(
    // The value to prefill in the input box
    @Nullable value: String = null,
    // The text to display underneath the input box.
    @Nullable prompt: String = null,
    // An optional string to show as place holder in the input box to guide the user what to type.
    @Nullable placeholder: String = null,
    // Set to `true` to show a password prompt that will not show the typed value.
    @Nullable password: java.lang.Boolean = null,
    // Set to `true` to keep the input box open when focus moves to another
    // part of the editor or to another window.
    @Nullable ignoreFocusOut: java.lang.Boolean = null,
    @Nullable valueSelection: Array[Int] = null
)

case class MetalsInputBoxResult(
    // value=null when cancelled=true
    @Nullable value: String = null,
    @Nullable cancelled: java.lang.Boolean = null
)

case class MetalsQuickPickParams(
    items: java.util.List[MetalsQuickPickItem],
    // An optional flag to include the description when filtering the picks.
    @Nullable matchOnDescription: java.lang.Boolean = null,
    // An optional flag to include the detail when filtering the picks.
    @Nullable matchOnDetail: java.lang.Boolean = null,
    // An optional string to show as place holder in the input box to guide the user what to pick on.
    @Nullable placeHolder: String = null,
    // Set to `true` to keep the picker open when focus moves to another part of the editor or to another window.
    @Nullable ignoreFocusOut: java.lang.Boolean = null
)

case class MetalsQuickPickResult(
    // value=null when cancelled=true
    @Nullable itemId: String = null,
    @Nullable cancelled: java.lang.Boolean = null
)

case class MetalsQuickPickItem(
    id: String,
    // A human readable string which is rendered prominent.
    label: String,
    // A human readable string which is rendered less prominent.
    @Nullable description: String = null,
    // A human readable string which is rendered less prominent.
    @Nullable detail: String = null,
    // Always show this item.
    @Nullable alwaysShow: java.lang.Boolean = null
)
