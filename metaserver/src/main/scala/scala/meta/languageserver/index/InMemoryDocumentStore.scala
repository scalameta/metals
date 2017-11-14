package scala.meta.languageserver.index

import java.net.URI
import java.util
import java.util.concurrent.ConcurrentHashMap
import org.langmeta.internal.semanticdb.schema.Document

class InMemoryDocumentStore(
    documents: util.Map[URI, Document] = new ConcurrentHashMap()
) extends DocumentStore {
  override def getDocument(uri: URI): Option[Document] =
    Option(documents.get(uri))
  override def putDocument(uri: URI, document: Document): Unit =
    documents.put(uri, document)
}
