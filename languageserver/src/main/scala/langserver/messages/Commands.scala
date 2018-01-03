package langserver.messages

import langserver.types._
import play.api.libs.json.OFormat
import play.api.libs.json._


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
  capabilities: ClientCapabilities)
object InitializeParams {
  implicit val format: OFormat[InitializeParams] = Json.format[InitializeParams]
}

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

case class CompletionList(isIncomplete: Boolean, items: Seq[CompletionItem])
object CompletionList {
  implicit val format: OFormat[CompletionList] = Json.format[CompletionList]
}

case class InitializeResult(capabilities: ServerCapabilities)
object InitializeResult {
  implicit val format: OFormat[InitializeResult] = Json.format[InitializeResult]
}

case class Shutdown()

case class ShutdownResult()
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
)

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
object TextDocumentPositionParams {
  implicit val format: OFormat[TextDocumentPositionParams] = Json.format[TextDocumentPositionParams]
}
case class ReferenceParams(
  textDocument: TextDocumentIdentifier,
  position: Position,
  context: ReferenceContext
)
object ReferenceParams {
  implicit val format: OFormat[ReferenceParams] = Json.format[ReferenceParams]
}

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

case class CodeActionRequest(params: CodeActionParams)
object CodeActionRequest {
  implicit val format: OFormat[CodeActionRequest] = Json.format[CodeActionRequest]
}
case class DocumentSymbolParams(textDocument: TextDocumentIdentifier)
object DocumentSymbolParams {
  implicit val format: OFormat[DocumentSymbolParams] = Json.format[DocumentSymbolParams]
}
case class TextDocumentRenameRequest(params: RenameParams)
object TextDocumentRenameRequest {
  implicit val format: OFormat[TextDocumentRenameRequest] = Json.format[TextDocumentRenameRequest]
}
case class TextDocumentSignatureHelpRequest(params: TextDocumentPositionParams)
case class TextDocumentCompletionRequest(params: TextDocumentPositionParams)
case class TextDocumentDefinitionRequest(params: TextDocumentPositionParams)
case class TextDocumentReferencesRequest(params: ReferenceParams)
case class TextDocumentDocumentHighlightRequest(params: TextDocumentPositionParams)
case class TextDocumentHoverRequest(params: TextDocumentPositionParams)
case class TextDocumentFormattingRequest(params: DocumentFormattingParams)
case class WorkspaceExecuteCommandRequest(params: ExecuteCommandParams)
case class WorkspaceSymbolRequest(params: WorkspaceSymbolParams)
case class ApplyWorkspaceEditResponse(applied: Boolean)
object ApplyWorkspaceEditResponse {
  implicit val format: OFormat[ApplyWorkspaceEditResponse] = Json.format[ApplyWorkspaceEditResponse]
}
case class ApplyWorkspaceEditParams(label: Option[String], edit: WorkspaceEdit)
object ApplyWorkspaceEditParams {
  implicit val format: OFormat[ApplyWorkspaceEditParams] = Json.format[ApplyWorkspaceEditParams]
}

case class Hover(contents: Seq[MarkedString], range: Option[Range])
object Hover {
  implicit val format: OFormat[Hover] = Json.format[Hover]
}


///////////////////////////// Notifications ///////////////////////////////

// From server to client

case class ShowMessageParams(`type`: MessageType, message: String)
object ShowMessageParams {
   implicit val format: OFormat[ShowMessageParams] = Json.format[ShowMessageParams]
}
case class LogMessageParams(`type`: MessageType, message: String)
object LogMessageParams {
  implicit val format: OFormat[LogMessageParams] = Json.format[LogMessageParams]
}
case class PublishDiagnostics(uri: String, diagnostics: Seq[Diagnostic])
object PublishDiagnostics {
  implicit val format: OFormat[PublishDiagnostics] = Json.format[PublishDiagnostics]
}

// from client to server

case class Exit()

case class DidOpenTextDocumentParams(textDocument: TextDocumentItem)
object DidOpenTextDocumentParams {
  implicit val format: OFormat[DidOpenTextDocumentParams] = Json.format[DidOpenTextDocumentParams]
}
case class DidChangeTextDocumentParams(
  textDocument: VersionedTextDocumentIdentifier,
  contentChanges: Seq[TextDocumentContentChangeEvent])
object DidChangeTextDocumentParams {
  implicit val format: OFormat[DidChangeTextDocumentParams] = Json.format[DidChangeTextDocumentParams]
}

case class DidCloseTextDocumentParams(textDocument: TextDocumentIdentifier)
object DidCloseTextDocumentParams {
  implicit val format: OFormat[DidCloseTextDocumentParams] = Json.format[DidCloseTextDocumentParams]
}
case class DidSaveTextDocumentParams(textDocument: TextDocumentIdentifier)
object DidSaveTextDocumentParams {
  implicit val format: OFormat[DidSaveTextDocumentParams] = Json.format[DidSaveTextDocumentParams]
}
case class DidChangeWatchedFilesParams(changes: Seq[FileEvent])
object DidChangeWatchedFilesParams {
  implicit val format: OFormat[DidChangeWatchedFilesParams] = Json.format[DidChangeWatchedFilesParams]
}
case class DidChangeConfigurationParams(settings: JsValue)
object DidChangeConfigurationParams {
  implicit val format: OFormat[DidChangeConfigurationParams] = Json.format[DidChangeConfigurationParams]
}

case class Initialized()
object Initialized {
  implicit val format: Format[Initialized] = OFormat(
    Reads(jsValue => JsSuccess(Initialized())),
    OWrites[Initialized](s => Json.obj()))
}


case class CancelRequest(id: Int)

case class RenameResult(params: WorkspaceEdit)
object RenameResult {
  implicit val format: OFormat[RenameResult] = Json.format[RenameResult]
}
case class CodeActionResult(params: Seq[Command])
object CodeActionResult {
  implicit val format: OFormat[CodeActionResult] = Json.format[CodeActionResult]
}
case class DocumentSymbolResult(params: Seq[SymbolInformation])
case class DefinitionResult(params: Seq[Location])
case class ReferencesResult(params: Seq[Location])
case class DocumentHighlightResult(params: Seq[Location])
case class DocumentFormattingResult(params: Seq[TextEdit])
case class SignatureHelp(signatures: Seq[SignatureInformation],
                         activeSignature: Option[Int],
                         activeParameter: Option[Int])
object SignatureHelp {
  implicit val format: OFormat[SignatureHelp] = Json.format[SignatureHelp]
}
case object ExecuteCommandResult
case class WorkspaceSymbolResult(params: Seq[SymbolInformation])
object WorkspaceSymbolResult {
  implicit val format: OFormat[WorkspaceSymbolResult] = Json.format[WorkspaceSymbolResult]
}


// Errors
case class InvalidParamsResponseError(message: String) extends Exception(message)

