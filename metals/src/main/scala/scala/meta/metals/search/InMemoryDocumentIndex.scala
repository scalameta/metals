package scala.meta.metals.search

import java.util
import java.util.concurrent.ConcurrentHashMap
import scala.meta.metals.Uri
import org.langmeta.internal.semanticdb.schema.Document

class InMemoryDocumentIndex(
    documents: util.Map[Uri, Document] = new ConcurrentHashMap()
) extends DocumentIndex {
  override def getDocument(uri: Uri): Option[Document] =
    Option(documents.get(uri))
  override def putDocument(uri: Uri, document: Document): Unit = {
    if (!uri.isJar) {
      scribe.info(s"Storing in-memory document for uri $uri")
    }
    documents.put(uri, document)
  }
}
