package scala.meta.languageserver.search

import java.util
import java.util.concurrent.ConcurrentHashMap
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.schema.Document

class InMemoryDocumentIndex(
    documents: util.Map[String, Document] = new ConcurrentHashMap()
) extends DocumentIndex
    with LazyLogging {
  override def getDocument(uri: String): Option[Document] =
    Option(documents.get(uri))
  override def putDocument(uri: String, document: Document): Unit = {
    if (!uri.startsWith("jar:")) {
      logger.info(s"Storing in-memory document for uri $uri")
    }
    documents.put(uri, document)
  }
}
