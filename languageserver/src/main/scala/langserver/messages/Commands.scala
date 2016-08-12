package langserver.messages

import play.api.libs.json.JsObject
import com.dhpcs.jsonrpc._
import play.api.libs.json._
import langserver.types._

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
  capabilities: ClientCapabilities,

  /**
   * User provided initialization options.
   */
  initializationOptions: JsObject) extends ServerCommand

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
  textDocumentSync: Int = TextDocumentSyncKind.Full /*
  /**
   * The server provides hover support.
   */
  hoverProvider: Boolean,
  /**
   * The server provides completion support.
   */
  completionProvider: CompletionOptions,
  /**
   * The server provides signature help support.
   */
  signatureHelpProvider: SignatureHelpOptions,
  /**
   * The server provides goto definition support.
   */
  definitionProvider: Boolean,
  /**
   * The server provides find references support.
   */
  referencesProvider: Boolean,
  /**
   * The server provides document highlight support.
   */
  documentHighlightProvider: Boolean,
  /**
   * The server provides document symbol support.
   */
  documentSymbolProvider: Boolean,
  /**
   * The server provides workspace symbol support.
   */
  workspaceSymbolProvider: Boolean,
  /**
   * The server provides code actions.
   */
  codeActionProvider: Boolean,
  /**
   * The server provides code lens.
   */
  codeLensProvider: CodeLensOptions,
  /**
   * The server provides document formatting.
   */
  documentFormattingProvider: Boolean,
  /**
   * The server provides document range formatting.
   */
  documentRangeFormattingProvider: Boolean,
  /**
   * The server provides document formatting on typing.
   */
  documentOnTypeFormattingProvider: DocumentOnTypeFormattingOptions,
  /**
   * The server provides rename support.
   */
  renameProvider: Boolean
  */ ) extends ResultResponse

case class Shutdown() extends ServerCommand
object Shutdown {
  implicit val format: Format[Shutdown] = Format(
    Reads(jsValue => JsSuccess(Shutdown())),
    Writes(s => Json.obj()))
}

case class ShowMessageRequestParams(
  /**
   * The message type. @see MessageType
   */
  tpe: Long,

  /**
   * The actual message
   */
  message: String,

  /**
   * The message action items to present.
   */
  actions: Seq[MessageActionItem]) extends ClientCommand

/**
 * A short title like 'Retry', 'Open Log' etc.
 */
case class MessageActionItem(title: String)
object MessageActionItem {
  implicit val format = Json.format[MessageActionItem]
}

case class TextDocumentPositionParams(textDocument: TextDocumentIdentifier, position: Position) extends ServerCommand

case class DocumentSymbolParams(textDocument: TextDocumentIdentifier) extends ServerCommand

object ServerCommand extends CommandCompanion[ServerCommand] {
  override val CommandTypeFormats = Message.MethodFormats(
    "initialize" -> Json.format[InitializeParams],
    "shutdown" -> Shutdown.format,
    "textDocument/completion" -> Json.format[TextDocumentPositionParams],
    "textDocument/documentSymbol" -> Json.format[DocumentSymbolParams])
}

object ClientCommand extends CommandCompanion[ClientCommand] {
  override val CommandTypeFormats = Message.MethodFormats(
    "window/showMessageRequest" -> Json.format[ShowMessageRequestParams])
}

///////////////////////////// Notifications ///////////////////////////////

// From server to client

case class ShowMessageParams(tpe: Long, message: String) extends Notification
case class LogMessageParams(tpe: Long, message: String) extends Notification
case class PublishDiagnostics(uri: String, diagnostics: Seq[Diagnostic]) extends Notification

// from client to server

case class ExitNotification() extends Notification
case class DidOpenTextDocumentParams(textDocument: TextDocumentItem) extends Notification
case class DidChangeTextDocumentParams(
  textDocument: VersionedTextDocumentIdentifier,
  contentChanges: Seq[TextDocumentContentChangeEvent]) extends Notification

case class DidCloseTextDocumentParams(textDocument: TextDocumentIdentifier) extends Notification
case class DidSaveTextDocumentParams(textDocument: TextDocumentIdentifier) extends Notification
case class DidChangeWatchedFiles(changes: Seq[FileEvent]) extends Notification

case class FileEvent(uri: String, tpe: Int)
object FileEvent { implicit val format = Json.format[FileEvent] }

object FileChangeType {
  final val Created = 1
  final val Changed = 2
  final val Deleted = 3
}

object Notification extends NotificationCompanion[Notification] {
  override val NotificationFormats = Message.MethodFormats(
    "window/showMessage" -> Json.format[ShowMessageParams],
    "window/logMessage" -> Json.format[LogMessageParams],
    "textDocument/didOpen" -> Json.format[DidOpenTextDocumentParams],
    "textDocument/didChange" -> Json.format[DidChangeTextDocumentParams],
    "textDocument/didClose" -> Json.format[DidCloseTextDocumentParams],
    "textDocument/didSave" -> Json.format[DidSaveTextDocumentParams],
    "workspace/didChangeWatchedFiles" -> Json.format[DidChangeWatchedFiles])
}

object ResultResponse extends ResponseCompanion[ResultResponse] {
  override val ResponseFormats = Message.MethodFormats(
    "initialize" -> Json.format[ServerCapabilities])
}
