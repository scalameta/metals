package langserver.core

import java.io.InputStream
import java.io.OutputStream
import com.typesafe.scalalogging.LazyLogging
import langserver.messages._
import langserver.types._
import monix.eval.Task
import monix.execution.Scheduler

/**
 * A language server implementation. Users should subclass this class and implement specific behavior.
 */
class LanguageServer(inStream: InputStream, outStream: OutputStream)(implicit s: Scheduler) extends LazyLogging {
  val connection: Connection = new Connection(inStream, outStream) {
    override def commandHandler(method: String, command: ServerCommand): Task[ResultResponse] = (method, command) match {
      case ("initialize", request: InitializeParams) => initialize(request)
      case ("textDocument/completion", request: TextDocumentCompletionRequest) => completion(request)
      case ("textDocument/definition", request: TextDocumentDefinitionRequest) => definition(request)
      case ("textDocument/documentHighlight", request: TextDocumentDocumentHighlightRequest) => documentHighlight(request)
      case ("textDocument/documentSymbol", request: DocumentSymbolParams) => documentSymbol(request)
      case ("textDocument/formatting", request: TextDocumentFormattingRequest) => formatting(request)
      case ("textDocument/hover", request: TextDocumentHoverRequest) => hover(request)
      case ("textDocument/references", request: TextDocumentReferencesRequest) => references(request)
      case ("textDocument/rename", request: TextDocumentRenameRequest) => rename(request)
      case ("textDocument/signatureHelp", request: TextDocumentSignatureHelpRequest) => signatureHelp(request)
      case ("workspace/executeCommand", request: WorkspaceExecuteCommandRequest) => executeCommand(request).map(_ => ExecuteCommandResult)
      case ("workspace/symbol", request: WorkspaceSymbolRequest) => workspaceSymbol(request)
      case ("shutdown", _: Shutdown) => shutdown()
      case c => Task.raiseError(new IllegalArgumentException(s"Unknown command $c"))
    }
  }

  connection.notificationHandlers += {
    case Initialized() => logger.info("Client has initialized")
    case Exit() => onExit()
    case DidOpenTextDocumentParams(td) => onOpenTextDocument(td)
    case DidChangeTextDocumentParams(td, changes) => onChangeTextDocument(td, changes)
    case DidSaveTextDocumentParams(td) => onSaveTextDocument(td)
    case DidCloseTextDocumentParams(td) => onCloseTextDocument(td)
    case DidChangeWatchedFiles(changes) => onChangeWatchedFiles(changes)
    case e => logger.error(s"Unknown notification $e")
  }


  // lifecycle
  def start(): Unit = {
    connection.start()
  }
  def onExit(): Unit = {
    logger.debug("exit")
    // TODO: should exit with success code 0 if the shutdown request has been received before; otherwise with error code 1
    sys.exit(0)
  }

  def initialize(processId: Long, rootPath: String, capabilities: ClientCapabilities): Task[InitializeResult] =
    initialize(InitializeParams(processId, rootPath, capabilities))
  def initialize(request: InitializeParams): Task[InitializeResult] = Task.now {
    logger.debug(s"initialize with $request")
    InitializeResult(ServerCapabilities())
  }
  def shutdown(): Task[ShutdownResult] =
    Task.now(ShutdownResult())

  // textDocument
  def completion(request: TextDocumentCompletionRequest): Task[CompletionList] = Task.now(CompletionList(isIncomplete = false, Nil))
  def definition(request: TextDocumentDefinitionRequest): Task[DefinitionResult] = Task.now(DefinitionResult(Nil))
  def documentHighlight(request: TextDocumentDocumentHighlightRequest): Task[DocumentHighlightResult] = Task.now(DocumentHighlightResult(Nil))
  def documentSymbol(request: DocumentSymbolParams): Task[DocumentSymbolResult] = Task.now(DocumentSymbolResult(Nil))
  def formatting(request: TextDocumentFormattingRequest): Task[DocumentFormattingResult] = Task.now(DocumentFormattingResult(Nil))
  def hover(request: TextDocumentHoverRequest): Task[Hover] = Task.now(Hover(Nil, None))
  def references(request: TextDocumentReferencesRequest): Task[ReferencesResult] = Task.now(ReferencesResult(Nil))
  def rename(request: TextDocumentRenameRequest): Task[RenameResult] = Task.now(RenameResult(WorkspaceEdit(Map.empty)))
  def signatureHelp(request: TextDocumentSignatureHelpRequest): Task[SignatureHelpResult] = Task.now(SignatureHelpResult(Nil, None, None))

  // workspace
  def executeCommand(request: WorkspaceExecuteCommandRequest): Task[Unit] = Task.now(())
  def workspaceSymbol(request: WorkspaceSymbolRequest): Task[WorkspaceSymbolResult] = Task.now(WorkspaceSymbolResult(Nil))

  def onOpenTextDocument(td: TextDocumentItem): Unit = {
    logger.debug(s"openTextDocument $td")
  }

  def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]): Unit = {
    logger.debug(s"changeTextDocument $td")
  }

  def onSaveTextDocument(td: TextDocumentIdentifier): Unit = {
    logger.debug(s"saveTextDocument $td")
  }

  def onCloseTextDocument(td: TextDocumentIdentifier): Unit = {
    logger.debug(s"closeTextDocument $td")
  }

  def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    logger.debug(s"changeWatchedFiles $changes")

}
