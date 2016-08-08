package langserver.messages

import play.api.libs.json.JsObject

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
  initializationOptions: JsObject)

case class InitializeError(retry: Boolean)
  
case class ClientCapabilities()

object TextDocumentSyncKind {
	/**
	 * Documents should not be synced at all.
	 */
	final val None = 0

	/**
	 * Documents are synced by always sending the full content
	 * of the document.
	 */
	final val Full = 1
	
	/**
	 * Documents are synced by sending the full content on open.
	 * After that only incremental updates to the document are
	 * send.
	 */
	final val Incremental = 2
}

case class ServerCapabilities(
  /**
	 * Defines how text documents are synced.
	 */
	textDocumentSync: Int = TextDocumentSyncKind.Full
	)
	/*
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
	*/

