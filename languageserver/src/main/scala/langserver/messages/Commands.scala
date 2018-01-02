package langserver.messages

import com.dhpcs.jsonrpc._
import play.api.libs.json._
import langserver.types._
import langserver.utils.JsonRpcUtils
import play.api.libs.json.OFormat
import scodec.Codec
import scodec.codecs._
import langserver.utils.ScodecUtils._
import scodec.bits.ByteVector

case class RawLspMessage(mimeType: Option[String], body: ByteVector)
object RawLspMessage {
  val CONTENT_LENGTH_KEY = "Content-Length"
  val CONTENT_TYPE_KEY = "Content-Type"
  val codec: Codec[RawLspMessage] =
    (bracketedBy("Content-Length:", "\r\n").xmap[Int](_.trim.toInt, _.toString)
      ~ optional(lookaheadIssue98Patch(constant('C')), bracketedBy("Content-Type:", "\r\n"))
      ).dropRight(constant(ByteVector('\r', '\n')))
      .consume { case (len, mime) =>
        bytes(len).xmap[RawLspMessage](bytes => RawLspMessage(mime, bytes), lsp => lsp.body)
      }(lsp => (lsp.body.length.toInt, lsp.mimeType))

  val listCodec: Codec[List[RawLspMessage]] = list(codec)
}

sealed trait Message
sealed trait ServerCommand extends Message
sealed trait ClientCommand extends Message

sealed trait Response extends Message
sealed trait ResultResponse extends Response

sealed trait Notification extends Message

/**
 * Parameters and types used in the `initialize` message.
 */
case class InitializeParams(
  /**
   * The process Id of the parent process that started
   * the server.
   */
  processId: Long,

  /**
   * The rootPath of the workspace. Is null
   * if no folder is open.
   */
  rootPath: String,

  /**
   * The capabilities provided by the client (editor)
   */
  capabilities: ClientCapabilities) extends ServerCommand

case class InitializeError(retry: Boolean)

case class ClientCapabilities()

object ClientCapabilities {
  implicit val format: Format[ClientCapabilities] = Format(
    Reads(jsValue => JsSuccess(ClientCapabilities())),
    Writes(c => Json.obj()))
}

case class ServerCapabilities(
  /**
   * Defines how text documents are synced.
   */
  textDocumentSync: TextDocumentSyncKind = TextDocumentSyncKind.Full,
  /**
   * The server provides hover support.
   */
  hoverProvider: Boolean = false,
  /**
   * The server provides completion support.
   */
  completionProvider: Option[CompletionOptions] = None,
  /**
   * The server provides signature help support.
   */
  signatureHelpProvider: Option[SignatureHelpOptions] = None,
  /**
   * The server provides goto definition support.
   */
  definitionProvider: Boolean = false,
  /**
   * The server provides find references support.
   */
  referencesProvider: Boolean = false,
  /**
   * The server provides document highlight support.
   */
  documentHighlightProvider: Boolean = false,
  /**
   * The server provides document symbol support.
   */
  documentSymbolProvider: Boolean = false,
  /**
   * The server provides workspace symbol support.
   */
  workspaceSymbolProvider: Boolean = false,
  /**
   * The server provides code actions.
   */
  codeActionProvider: Boolean = false,
  /**
   * The server provides code lens.
   */
  codeLensProvider: Option[CodeLensOptions] = None,
  /**
   * The server provides document formatting.
   */
  documentFormattingProvider: Boolean = false,
  /**
   * The server provides document range formatting.
   */
  documentRangeFormattingProvider: Boolean = false,
  /**
   * The server provides document formatting on typing.
   */
  documentOnTypeFormattingProvider: Option[DocumentOnTypeFormattingOptions] = None,
  /**
   * The server provides rename support.
   */
  renameProvider: Boolean = false,
  /**
   * The server provides execute command support.
   */
  executeCommandProvider: ExecuteCommandOptions = ExecuteCommandOptions(Nil)
)

object ServerCapabilities {
  implicit val format: OFormat[ServerCapabilities] = Json.format[ServerCapabilities]
}

case class CompletionOptions(resolveProvider: Boolean, triggerCharacters: Seq[String])
object CompletionOptions {
  implicit val format: Format[CompletionOptions] = Json.format[CompletionOptions]
}

case class SignatureHelpOptions(triggerCharacters: Seq[String])
object SignatureHelpOptions {
  implicit val format: Format[SignatureHelpOptions] = Json.format[SignatureHelpOptions]
}

case class CodeLensOptions(resolveProvider: Boolean = false)
object CodeLensOptions {
  implicit val format: Format[CodeLensOptions] = Json.format[CodeLensOptions]
}

case class DocumentOnTypeFormattingOptions(firstTriggerCharacter: String, moreTriggerCharacters: Seq[String])
object DocumentOnTypeFormattingOptions {
  implicit val format: Format[DocumentOnTypeFormattingOptions] = Json.format[DocumentOnTypeFormattingOptions]
}

case class ExecuteCommandOptions(commands: Seq[String])
object ExecuteCommandOptions {
  implicit val format: Format[ExecuteCommandOptions] = Json.format[ExecuteCommandOptions]
}

case class CompletionList(isIncomplete: Boolean, items: Seq[CompletionItem]) extends ResultResponse
object CompletionList {
  implicit val format: OFormat[CompletionList] = Json.format[CompletionList]
}

case class InitializeResult(capabilities: ServerCapabilities) extends ResultResponse

case class Shutdown() extends ServerCommand

case class ShutdownResult() extends ResultResponse
object ShutdownResult {
  implicit val format: Format[ShutdownResult] = OFormat(
    Reads(jsValue => JsSuccess(ShutdownResult())),
    OWrites[ShutdownResult](s => Json.obj()))
}

/**
  * The show message request is sent from a server to a client to ask the client to display a
  * particular message in the user interface. In addition to the show message notification the
  * request allows to pass actions and to wait for an answer from the client.
  *
  * @param `type` The message type. @see [[MessageType]]
  * @param message The actual message
  * @param actions The message action items to present.
  */
case class ShowMessageRequestParams(
  `type`: MessageType,
  message: String,
  actions: Seq[MessageActionItem]
) extends ClientCommand

/**
 * A short title like 'Retry', 'Open Log' etc.
 */
case class MessageActionItem(title: String)
object MessageActionItem {
  implicit val format: OFormat[MessageActionItem] = Json.format[MessageActionItem]
}

case class TextDocumentPositionParams(
  textDocument: TextDocumentIdentifier,
  position: Position
)
case class ReferenceParams(
  textDocument: TextDocumentIdentifier,
  position: Position,
  context: ReferenceContext
)

case class RenameParams(
  textDocument: TextDocumentIdentifier,
  position: Position,
  newName: String
)
object RenameParams {
  implicit val format: OFormat[RenameParams] = Json.format[RenameParams]
}

case class CodeActionParams(
  textDocument: TextDocumentIdentifier,
  range: Range,
  context: CodeActionContext
)
object CodeActionParams {
  implicit val format: OFormat[CodeActionParams] = Json.format[CodeActionParams]
}

case class CodeActionRequest(params: CodeActionParams) extends ServerCommand
object CodeActionRequest {
  implicit val format: OFormat[CodeActionRequest] = Json.format[CodeActionRequest]
}
case class DocumentSymbolParams(textDocument: TextDocumentIdentifier) extends ServerCommand
case class TextDocumentRenameRequest(params: RenameParams) extends ServerCommand
object TextDocumentRenameRequest {
  implicit val format: OFormat[TextDocumentRenameRequest] = Json.format[TextDocumentRenameRequest]
}
case class TextDocumentSignatureHelpRequest(params: TextDocumentPositionParams) extends ServerCommand
case class TextDocumentCompletionRequest(params: TextDocumentPositionParams) extends ServerCommand
case class TextDocumentDefinitionRequest(params: TextDocumentPositionParams) extends ServerCommand
case class TextDocumentReferencesRequest(params: ReferenceParams) extends ServerCommand
case class TextDocumentDocumentHighlightRequest(params: TextDocumentPositionParams) extends ServerCommand
case class TextDocumentHoverRequest(params: TextDocumentPositionParams) extends ServerCommand
case class TextDocumentFormattingRequest(params: DocumentFormattingParams) extends ServerCommand
case class WorkspaceExecuteCommandRequest(params: WorkspaceExecuteCommandParams) extends ServerCommand
case class WorkspaceSymbolRequest(params: WorkspaceSymbolParams) extends ServerCommand
case class ApplyWorkspaceEditParams(label: Option[String], edit: WorkspaceEdit)
object ApplyWorkspaceEditParams {
  implicit val format: OFormat[ApplyWorkspaceEditParams] = Json.format[ApplyWorkspaceEditParams]
}

case class Hover(contents: Seq[MarkedString], range: Option[Range]) extends ResultResponse
object Hover {
  implicit val format: OFormat[Hover] = Json.format[Hover]
}

object ServerCommand extends CommandCompanion[ServerCommand] {
  import JsonRpcUtils._

  implicit val positionParamsFormat: OFormat[TextDocumentPositionParams] = Json.format[TextDocumentPositionParams]
  implicit val referenceParamsFormat: OFormat[ReferenceParams] = Json.format[ReferenceParams]

  override val CommandFormats = Message.MessageFormats(
    "initialize" -> Json.format[InitializeParams],
    "textDocument/completion" -> valueFormat(TextDocumentCompletionRequest)(_.params),
    "textDocument/codeAction" -> valueFormat(CodeActionRequest.apply)(_.params),
    "textDocument/rename" -> valueFormat(TextDocumentRenameRequest.apply)(_.params),
    "textDocument/signatureHelp" -> valueFormat(TextDocumentSignatureHelpRequest)(_.params),
    "textDocument/definition" -> valueFormat(TextDocumentDefinitionRequest)(_.params),
    "textDocument/references" -> valueFormat(TextDocumentReferencesRequest)(_.params),
    "textDocument/documentHighlight" -> valueFormat(TextDocumentDocumentHighlightRequest)(_.params),
    "textDocument/hover" -> valueFormat(TextDocumentHoverRequest)(_.params),
    "textDocument/documentSymbol" -> Json.format[DocumentSymbolParams],
    "textDocument/formatting" -> valueFormat(TextDocumentFormattingRequest)(_.params),
    "workspace/executeCommand" -> valueFormat(WorkspaceExecuteCommandRequest)(_.params),
    "workspace/symbol" -> valueFormat(WorkspaceSymbolRequest)(_.params),
  )

  // NOTE: this is a workaround to read `shutdown` request which doesn't have parameters (scala-json-rpc requires parameters for all requests)
  override def read(jsonRpcRequestMessage: JsonRpcRequestMessage): JsResult[_ <: ServerCommand] = {
    jsonRpcRequestMessage.method match {
      case "shutdown" => JsSuccess(Shutdown())
      case _ => super.read(jsonRpcRequestMessage)
    }
  }
}

object ClientCommand extends CommandCompanion[ClientCommand] {
  override val CommandFormats = Message.MessageFormats(
    "window/showMessageRequest" -> Json.format[ShowMessageRequestParams])
}

///////////////////////////// Notifications ///////////////////////////////

// From server to client

case class ShowMessageParams(`type`: MessageType, message: String) extends Notification
case class LogMessageParams(`type`: MessageType, message: String) extends Notification
case class PublishDiagnostics(uri: String, diagnostics: Seq[Diagnostic]) extends Notification
object PublishDiagnostics {
  implicit val format: OFormat[PublishDiagnostics] = Json.format[PublishDiagnostics]
}

// from client to server

case class Exit() extends Notification

case class DidOpenTextDocumentParams(textDocument: TextDocumentItem) extends Notification
case class DidChangeTextDocumentParams(
  textDocument: VersionedTextDocumentIdentifier,
  contentChanges: Seq[TextDocumentContentChangeEvent]) extends Notification

case class DidCloseTextDocumentParams(textDocument: TextDocumentIdentifier) extends Notification
case class DidSaveTextDocumentParams(textDocument: TextDocumentIdentifier) extends Notification
case class DidChangeWatchedFilesParams(changes: Seq[FileEvent]) extends Notification
case class DidChangeConfigurationParams(settings: JsValue) extends Notification

case class Initialized() extends Notification
object Initialized {
  implicit val format: Format[Initialized] = OFormat(
    Reads(jsValue => JsSuccess(Initialized())),
    OWrites[Initialized](s => Json.obj()))
}


case class CancelRequest(id: Int) extends Notification

object Notification extends NotificationCompanion[Notification] {
  override val NotificationFormats = Message.MessageFormats(
    "window/showMessage" -> Json.format[ShowMessageParams],
    "window/logMessage" -> Json.format[LogMessageParams],
    "textDocument/publishDiagnostics" -> Json.format[PublishDiagnostics],
    "textDocument/didOpen" -> Json.format[DidOpenTextDocumentParams],
    "textDocument/didChange" -> Json.format[DidChangeTextDocumentParams],
    "textDocument/didClose" -> Json.format[DidCloseTextDocumentParams],
    "textDocument/didSave" -> Json.format[DidSaveTextDocumentParams],
    "workspace/didChangeWatchedFiles" -> Json.format[DidChangeWatchedFilesParams],
    "workspace/didChangeConfiguration" -> Json.format[DidChangeConfigurationParams],
    "initialized" -> Initialized.format,
    "$/cancelRequest" -> Json.format[CancelRequest]
  )

  // NOTE: this is a workaround to read `exit` notification which doesn't have parameters (scala-json-rpc requires parameters for all notifications)
  override def read(jsonRpcNotificationMessage: JsonRpcNotificationMessage): JsResult[_ <: Notification] = {
    jsonRpcNotificationMessage.method match {
      case "exit" => JsSuccess(Exit())
      case _ => super.read(jsonRpcNotificationMessage)
    }
  }
}

case class RenameResult(params: WorkspaceEdit) extends ResultResponse
object RenameResult {
  implicit val format: OFormat[RenameResult] = Json.format[RenameResult]
}
case class CodeActionResult(params: Seq[Command]) extends ResultResponse
object CodeActionResult {
  implicit val format: OFormat[CodeActionResult] = Json.format[CodeActionResult]
}
case class DocumentSymbolResult(params: Seq[SymbolInformation]) extends ResultResponse
case class DefinitionResult(params: Seq[Location]) extends ResultResponse
case class ReferencesResult(params: Seq[Location]) extends ResultResponse
case class DocumentHighlightResult(params: Seq[Location]) extends ResultResponse
case class DocumentFormattingResult(params: Seq[TextEdit]) extends ResultResponse
case class SignatureHelpResult(signatures: Seq[SignatureInformation],
                               activeSignature: Option[Int],
                               activeParameter: Option[Int]) extends ResultResponse
object SignatureHelpResult {
  implicit val format: OFormat[SignatureHelpResult] = Json.format[SignatureHelpResult]
}
case object ExecuteCommandResult extends ResultResponse
case class WorkspaceSymbolResult(params: Seq[SymbolInformation]) extends ResultResponse
object WorkspaceSymbolResult {
  implicit val format: OFormat[WorkspaceSymbolResult] = Json.format[WorkspaceSymbolResult]
}

object ResultResponse extends ResponseCompanion[Any] {
  import JsonRpcUtils._

  override val ResponseFormats = Message.MessageFormats(
    "initialize" -> Json.format[InitializeResult],
    "textDocument/completion" -> Json.format[CompletionList],
    "textDocument/codeAction" -> valueFormat(CodeActionResult.apply)(_.params),
    "textDocument/rename" -> valueFormat(RenameResult.apply)(_.params),
    "textDocument/signatureHelp" -> Json.format[SignatureHelpResult],
    "textDocument/definition" -> valueFormat(DefinitionResult)(_.params),
    "textDocument/references" -> valueFormat(ReferencesResult)(_.params),
    "textDocument/documentHighlight" -> valueFormat(DocumentHighlightResult)(_.params),
    "textDocument/hover" -> Json.format[Hover],
    "textDocument/documentSymbol" -> valueFormat(DocumentSymbolResult)(_.params),
    "textDocument/formatting" -> valueFormat(DocumentFormattingResult)(_.params),
    "workspace/executeCommand" -> emptyFormat[ExecuteCommandResult.type],
    "workspace/symbol" -> valueFormat(WorkspaceSymbolResult.apply)(_.params),
    "shutdown" -> ShutdownResult.format
  )
}

// Errors
case class InvalidParamsResponseError(message: String) extends Exception(message) with Response

