package scala.meta.languageserver.search

import scala.meta.languageserver.Uri
import scala.meta.internal.semanticdb3.TextDocument

trait DocumentIndex {
  def getDocument(uri: Uri): Option[TextDocument] // should this be future?
  def putDocument(uri: Uri, document: TextDocument): Unit
}
