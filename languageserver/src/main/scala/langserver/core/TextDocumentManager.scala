package langserver.core

import langserver.messages._
import langserver.types.TextDocumentContentChangeEvent
import langserver.types.TextDocumentIdentifier
import langserver.types.TextDocumentItem
import langserver.types.VersionedTextDocumentIdentifier

class TextDocumentManager(connection: ConnectionImpl) {
  connection.notificationHandlers += {
    case DidOpenTextDocumentParams(td) => onOpenTextDocument(td)
    case DidChangeTextDocumentParams(td, changes) => onChangeTextDocument(td, changes)
    case DidCloseTextDocumentParams(td) => onCloseTextDocument(td)
    case e => ()
  }

  def onOpenTextDocument(td: TextDocumentItem) = {

  }

  def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]) = {

  }

  def onCloseTextDocument(td: TextDocumentIdentifier) = {

  }

}
