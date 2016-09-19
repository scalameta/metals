package langserver.core

import java.io.InputStream
import java.io.OutputStream

import com.typesafe.scalalogging.LazyLogging

import langserver.messages._
import langserver.types._
import play.api.libs.json.JsObject

/**
 * A language server implementation. Users should subclass this class and implement specific behavior.
 */
class LanguageServer(inStream: InputStream, outStream: OutputStream) extends LazyLogging {
  val connection = (new ConnectionImpl(inStream, outStream)) {
    case InitializeParams(pid, rootPath, capabilities) =>
      InitializeResult(initialize(pid, rootPath, capabilities))
    case TextDocumentPositionParams(textDocument, position) =>
      completionRequest(textDocument, position)
    case Shutdown() =>
      shutdown()
      ShutdownResult(0) // the value is a dummy, because Play Json needs to serialize something
    case c =>
      logger.error(s"Unknown command $c")
      sys.error("Unknown command")
  }

  connection.notificationHandlers += {
    case DidOpenTextDocumentParams(td) => onOpenTextDocument(td)
    case DidChangeTextDocumentParams(td, changes) => onChangeTextDocument(td, changes)
    case DidSaveTextDocumentParams(td) => onSaveTextDocument(td)
    case DidCloseTextDocumentParams(td) => onCloseTextDocument(td)
    case DidChangeWatchedFiles(changes) => onChangeWatchedFiles(changes)
    case e => logger.error(s"Unknown notification $e")
  }

  def start(): Unit = {
    connection.start()
  }

  def onOpenTextDocument(td: TextDocumentItem) = {
    logger.debug(s"openTextDocuemnt $td")
  }

  def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]) = {
    logger.debug(s"changeTextDocuemnt $td")
  }

  def onSaveTextDocument(td: TextDocumentIdentifier) = {
    logger.debug(s"saveTextDocuemnt $td")
    connection.showMessage(MessageType.Info, s"Saved text document ${td.uri}")
  }

  def onCloseTextDocument(td: TextDocumentIdentifier) = {
    logger.debug(s"closeTextDocuemnt $td")
  }

  def onChangeWatchedFiles(changes: Seq[FileEvent]) = {
//    ???
  }

  def initialize(pid: Long, rootPath: String, capabilities: ClientCapabilities): ServerCapabilities = {
    logger.info(s"Initialized with $pid, $rootPath, $capabilities")
    ServerCapabilities(completionProvider = Some(CompletionOptions(false, Seq("."))))
  }

  def completionRequest(textDocument: TextDocumentIdentifier, position: Position): ResultResponse = {
    CompletionList(isIncomplete = false, Nil)
  }

  def shutdown(): Unit = {

  }
}
