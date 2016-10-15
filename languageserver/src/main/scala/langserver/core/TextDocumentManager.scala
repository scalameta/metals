package langserver.core

import langserver.messages._
import langserver.types._
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentHashMap
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._


/**
 * A class to manage text documents coming over the wire from a Language Server client.
 *
 * The manager keeps an up to date version of each document that is currently open by the client.
 */
class TextDocumentManager(connection: Connection) extends LazyLogging {
  connection.notificationHandlers += {
    case DidOpenTextDocumentParams(td) => onOpenTextDocument(td)
    case DidChangeTextDocumentParams(td, changes) => onChangeTextDocument(td, changes)
    case DidCloseTextDocumentParams(td) => onCloseTextDocument(td)
    case e => ()
  }

  private val docs: ConcurrentMap[String, TextDocument] = new ConcurrentHashMap

  def documentForUri(uri: String): Option[TextDocument] =
    Option(docs.get(uri))

  def allOpenDocuments: Seq[TextDocument] = docs.values.asScala.toSeq

  def onOpenTextDocument(td: TextDocumentItem) = {
    docs.put(td.uri, new TextDocument(td.uri, td.text.toCharArray))
  }

  def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]) = {
    docs.get(td.uri) match {
      case null =>
        logger.error(s"Document ${td.uri} not found in this manager. Adding now")
        // we assume full text sync
        docs.put(td.uri, new TextDocument(td.uri, changes.head.text.toCharArray))
      case doc =>
        docs.put(td.uri, doc.applyChanges(changes))
    }
  }

  def onCloseTextDocument(td: TextDocumentIdentifier) = {
    docs.remove(td.uri)
  }

}
