package scala.meta.languageserver.search

import org.langmeta.internal.semanticdb.schema.Document

trait DocumentIndex {
  def getDocument(uri: String): Option[Document] // should this be future?
  def putDocument(uri: String, document: Document): Unit
}
