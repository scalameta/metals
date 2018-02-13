package scala.meta.metals.search

import java.util
import java.util.concurrent.ConcurrentHashMap
import scala.meta.metals.Uri
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.schema.Document

class InMemoryDocumentIndex(
    documents: util.Map[Uri, Document] = new ConcurrentHashMap()
) extends DocumentIndex
    with LazyLogging {
  override def getDocument(uri: Uri): Option[Document] =
    Option(documents.get(uri))
  override def putDocument(uri: Uri, document: Document): Unit = {
    if (!uri.isJar) {
      logger.info(s"Storing in-memory document for uri $uri")
    }
    documents.put(uri, document)
  }
}
