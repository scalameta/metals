package scala.meta.languageserver.index

import java.net.URI
import java.util
import java.util.concurrent.ConcurrentHashMap
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.schema.Document

class InMemoryDocumentStore(
    documents: util.Map[URI, Document] = new ConcurrentHashMap()
) extends DocumentStore
    with LazyLogging {
  override def getDocument(uri: URI): Option[Document] =
    Option(documents.get(uri))
  override def putDocument(uri: URI, document: Document): Unit = {
    logger.info(s"Storing in-memory document for uri $uri")
    documents.put(uri, document)
  }
}
