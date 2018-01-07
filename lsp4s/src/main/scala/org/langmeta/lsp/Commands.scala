package org.langmeta.lsp

import io.circe.Json
import io.circe.generic.JsonCodec

/**
 * Parameters and types used in the `initialize` message.
 */
@JsonCodec case class InitializeParams(
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
    capabilities: ClientCapabilities
)

@JsonCodec case class ClientCapabilities()

@JsonCodec case class ServerCapabilities(
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
    documentOnTypeFormattingProvider: Option[DocumentOnTypeFormattingOptions] =
      None,
    /**
     * The server provides rename support.
     */
    renameProvider: Boolean = false,
    /**
     * The server provides execute command support.
     */
    executeCommandProvider: ExecuteCommandOptions = ExecuteCommandOptions(Nil)
)

@JsonCodec case class CompletionOptions(
    resolveProvider: Boolean,
    triggerCharacters: Seq[String]
)
@JsonCodec case class SignatureHelpOptions(triggerCharacters: Seq[String])
@JsonCodec case class CodeLensOptions(resolveProvider: Boolean = false)
@JsonCodec case class DocumentOnTypeFormattingOptions(
    firstTriggerCharacter: String,
    moreTriggerCharacters: Seq[String]
)
@JsonCodec case class ExecuteCommandOptions(commands: Seq[String])
@JsonCodec case class CompletionList(
    isIncomplete: Boolean,
    items: Seq[CompletionItem]
)
@JsonCodec case class InitializeResult(capabilities: ServerCapabilities)
@JsonCodec case class Shutdown()
@JsonCodec case class ShutdownResult()

/**
 * The show message request is sent from a server to a client to ask the client to display a
 * particular message in the user interface. In addition to the show message notification the
 * request allows to pass actions and to wait for an answer from the client.
 *
 * @param `type` The message type. @see [[MessageType]]
 * @param message The actual message
 * @param actions The message action items to present.
 */
@JsonCodec case class ShowMessageRequestParams(
    `type`: MessageType,
    message: String,
    actions: Seq[MessageActionItem]
)

/**
 * A short title like 'Retry', 'Open Log' etc.
 */
@JsonCodec case class MessageActionItem(title: String)

@JsonCodec case class TextDocumentPositionParams(
    textDocument: TextDocumentIdentifier,
    position: Position
)

@JsonCodec case class ReferenceParams(
    textDocument: TextDocumentIdentifier,
    position: Position,
    context: ReferenceContext
)

@JsonCodec case class RenameParams(
    textDocument: TextDocumentIdentifier,
    position: Position,
    newName: String
)

@JsonCodec case class CodeActionParams(
    textDocument: TextDocumentIdentifier,
    range: Range,
    context: CodeActionContext
)

@JsonCodec case class CodeActionRequest(params: CodeActionParams)

@JsonCodec case class DocumentSymbolParams(textDocument: TextDocumentIdentifier)

@JsonCodec case class TextDocumentRenameRequest(params: RenameParams)

@JsonCodec case class ApplyWorkspaceEditResponse(applied: Boolean)
@JsonCodec case class ApplyWorkspaceEditParams(
    label: Option[String],
    edit: WorkspaceEdit
)

@JsonCodec case class Hover(contents: Seq[MarkedString], range: Option[Range])

///////////////////////////// Notifications ///////////////////////////////

// From server to client

@JsonCodec case class ShowMessageParams(`type`: MessageType, message: String)
@JsonCodec case class LogMessageParams(`type`: MessageType, message: String)
@JsonCodec case class PublishDiagnostics(
    uri: String,
    diagnostics: Seq[Diagnostic]
)

@JsonCodec case class DidOpenTextDocumentParams(textDocument: TextDocumentItem)
@JsonCodec case class DidChangeTextDocumentParams(
    textDocument: VersionedTextDocumentIdentifier,
    contentChanges: Seq[TextDocumentContentChangeEvent]
)
@JsonCodec case class DidCloseTextDocumentParams(
    textDocument: TextDocumentIdentifier
)
@JsonCodec case class DidSaveTextDocumentParams(
    textDocument: TextDocumentIdentifier
)
@JsonCodec case class DidChangeWatchedFilesParams(changes: Seq[FileEvent])
@JsonCodec case class DidChangeConfigurationParams(settings: Json)

@JsonCodec case class Initialized()

@JsonCodec case class CancelRequest(id: Int)

@JsonCodec case class CodeActionResult(params: Seq[Command])
@JsonCodec case class SignatureHelp(
    signatures: Seq[SignatureInformation],
    activeSignature: Option[Int],
    activeParameter: Option[Int]
)
@JsonCodec case class WorkspaceSymbolResult(params: Seq[SymbolInformation])
