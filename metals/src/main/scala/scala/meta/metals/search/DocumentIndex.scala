package scala.meta.metals.search

import scala.meta.metals.Uri
import org.langmeta.internal.semanticdb.schema.Document

trait DocumentIndex {
  def getDocument(uri: Uri): Option[Document] // should this be future?
  def putDocument(uri: Uri, document: Document): Unit
}
