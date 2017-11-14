package scala.meta.languageserver.index

import java.net.URI
import org.langmeta.internal.semanticdb.schema.Document

trait DocumentStore {
  def getDocument(uri: URI): Option[Document] // should this be future?
  def putDocument(uri: URI, document: Document): Unit
}
