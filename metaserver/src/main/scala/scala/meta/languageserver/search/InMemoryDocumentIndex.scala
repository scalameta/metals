package scala.meta.languageserver.search

import java.util
import java.util.concurrent.ConcurrentHashMap
import scala.meta.languageserver.Uri
import com.typesafe.scalalogging.LazyLogging
import scala.meta.internal.semanticdb3.TextDocument

class InMemoryDocumentIndex(
    documents: util.Map[Uri, TextDocument] = new ConcurrentHashMap()
) extends DocumentIndex
    with LazyLogging {
  override def getDocument(uri: Uri): Option[TextDocument] =
    Option(documents.get(uri))
  override def putDocument(uri: Uri, document: TextDocument): Unit = {
    if (!uri.isJar) {
      logger.info(s"Storing in-memory document for uri $uri")
    }
    documents.put(uri, document)
  }
}
