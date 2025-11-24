package scala.meta.internal.metals.clients.language

import java.util.concurrent.CompletableFuture
import javax.annotation.Nullable

import scala.util.Try

import scala.meta.internal.metals.Icons
import scala.meta.internal.tvp._

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient

trait MetalsLanguageClient extends LanguageClient with TreeViewClient {

  /**
   * Display message in the editor "status bar", which should be displayed somewhere alongside the buffer.
   *
   * The status bar should always be visible to the user.
   *
   * - VS Code: https://code.visualstudio.com/docs/extensionAPI/vscode-api#StatusBarItem
   */
  @JsonNotification("metals/status")
  def metalsStatus(params: MetalsStatusParams): Unit

  @JsonNotification("metals/executeClientCommand")
  def metalsExecuteClientCommand(params: ExecuteCommandParams): Unit

  def refreshModel(): CompletableFuture[Unit]

  /**
   * Opens an input box to ask the user for input. This method is used to deal with gson and existing extension protocol.
   * @return the user provided input.
   */
  @JsonRequest("metals/inputBox")
  private[clients] def rawMetalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[RawMetalsInputBoxResult]

  def showMessageRequest(
      params: ShowMessageRequestParams,
      defaultTo: () => MessageActionItem,
  ): CompletableFuture[MessageActionItem] = {
    CompletableFuture.completedFuture(defaultTo())
  }

  /**
   * Opens an input box to ask the user for input.
   *
   * @return the user provided input or None if request was cancelled.
   * The future can be cancelled, meaning the input box should be dismissed in the editor.
   */
  final def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[Option[MetalsInputBoxResult]] =
    rawMetalsInputBox(params).thenApply { result =>
      if (result.cancelled != null && result.cancelled)
        None
      else
        Option(result.value).map(MetalsInputBoxResult(_))
    }

  /**
   * Opens an menu to ask the user to pick one of the suggested options. This method is used to deal with gson and existing extension protocol.
   * @return the user provided pick.
   */
  @JsonRequest("metals/quickPick")
  private[clients] def rawMetalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[RawMetalsQuickPickResult]

  /**
   * Opens an menu to ask the user to pick one of the suggested options.
   *
   * @return the user provided pick or None if request was cancelled.
   * The future can be cancelled, meaning the input box should be dismissed in the editor.
   */
  final def metalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[Option[MetalsQuickPickResult]] =
    rawMetalsQuickPick(params).thenApply { result =>
      if (result.cancelled != null && result.cancelled)
        None
      else
        Option(result.itemId).map(MetalsQuickPickResult(_))
    }

  final def showMessage(messageType: MessageType, message: String): Unit = {
    val params = new MessageParams(messageType, message)
    showMessage(params)
  }

  override def createProgress(
      params: WorkDoneProgressCreateParams
  ): CompletableFuture[Void]

  override def notifyProgress(params: ProgressParams): Unit

  def shutdown(): Unit = {}

}

/**
 * These Raw classes below are used in communication between Metals server and clients.
 * They're gson-friendly, however because of nulls we don't want to use them in metals
 */
case class RawMetalsInputBoxResult(
    // value=null when cancelled=true
    @Nullable value: String = null,
    @Nullable cancelled: java.lang.Boolean = null,
)
case class RawMetalsQuickPickResult(
    // value=null when cancelled=true
    @Nullable itemId: String = null,
    @Nullable cancelled: java.lang.Boolean = null,
)

/**
 * Arguments for the metals/status notification.
 *
 * @param text The text to display in the status bar.
 * @param level info, warn, error
 * @param show if true, show the status bar.
 * @param hide if true, hide the status bar.
 * @param tooltip optional display this message when the user hovers over the status bar item.
 * @param command optional command that the client should trigger when the user clicks on
 *                the status bar item.
 * @param statusType is this a bsp or metals status.
 */
case class MetalsStatusParams(
    text: String,
    @Nullable level: String = "info",
    @Nullable show: java.lang.Boolean = null,
    @Nullable hide: java.lang.Boolean = null,
    @Nullable tooltip: String = null,
    @Nullable command: String = null,
    @Nullable metalsCommand: MetalsCommand = null,
    @Nullable commandTooltip: String = null,
    @Nullable statusType: String = StatusType.metals.toString(),
) {

  def logMessage(icons: Icons): String = {
    val decodedText =
      if (icons != Icons.unicode && icons != Icons.none)
        icons.all.foldLeft(text)((acc, icon) => acc.replace(icon, ""))
      else text
    if (
      statusType == StatusType.bsp.toString() || statusType == StatusType.module
        .toString()
    )
      if (level == "info") ""
      else s"$decodedText: $tooltip"
    else decodedText
  }

  def getStatusType: StatusType.Value =
    Try(StatusType.withName(statusType)).toOption.getOrElse(StatusType.metals)

  def withStatusType(statusType: StatusType.StatusType): MetalsStatusParams =
    this.copy(statusType = statusType.toString())
}

object StatusType extends Enumeration {
  type StatusType = Value
  val metals, bsp, module = Value
}

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
    @Nullable valueSelection: Array[Int] = null,
)

case class MetalsInputBoxResult(value: String) extends AnyVal

case class MetalsQuickPickParams(
    items: java.util.List[MetalsQuickPickItem],
    // An optional flag to include the description when filtering the picks.
    @Nullable matchOnDescription: java.lang.Boolean = null,
    // An optional flag to include the detail when filtering the picks.
    @Nullable matchOnDetail: java.lang.Boolean = null,
    // An optional string to show as place holder in the input box to guide the user what to pick on.
    @Nullable placeHolder: String = null,
    // Set to `true` to keep the picker open when focus moves to another part of the editor or to another window.
    @Nullable ignoreFocusOut: java.lang.Boolean = null,
)

case class MetalsQuickPickResult(itemId: String) extends AnyVal

case class MetalsOpenWindowParams(
    uri: String,
    openNewWindow: java.lang.Boolean,
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
    @Nullable alwaysShow: java.lang.Boolean = null,
)
