package langserver.core

import java.io.InputStream
import java.io.OutputStream

import com.typesafe.scalalogging.LazyLogging

import langserver.messages._
import langserver.types._
import play.api.libs.json.JsObject

class LanguageServer(inStream: InputStream, outStream: OutputStream) extends LazyLogging {
  val connection = (new ConnectionImpl(inStream, outStream)) {
    case InitializeParams(pid, rootPath, capabilities, options) => initialize(pid, rootPath, capabilities, options)
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

//  def initialize(params: InitializeParams): Unit

//  def shutdown(): Unit
//
//  def onDidChangeConfiguration(params: Any): Unit

  /**
   * A notification sent to the client to show a message.
   *
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def showMessage(tpe: Int, message: String): Unit = {
    connection.sendNotification(ShowMessageParams(tpe, message))
  }


  def onOpenTextDocument(td: TextDocumentItem) = {
    logger.debug(s"openTextDocuemnt $td")
  }

  def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]) = {
    logger.debug(s"changeTextDocuemnt $td")
  }

  def onSaveTextDocument(td: TextDocumentIdentifier) = {
    logger.debug(s"saveTextDocuemnt $td")
    showMessage(MessageType.Info, s"Saved text document ${td.uri}")
  }

  def onCloseTextDocument(td: TextDocumentIdentifier) = {
    logger.debug(s"closeTextDocuemnt $td")
  }

  def onChangeWatchedFiles(changes: Seq[FileEvent]) = {
//    ???
  }

  def initialize(pid: Long, rootPath: String, capabilities: ClientCapabilities, options: JsObject): ServerCapabilities = {
    logger.info(s"Initialized with $pid, $rootPath, $capabilities, $options")
    ServerCapabilities()
  }

}
