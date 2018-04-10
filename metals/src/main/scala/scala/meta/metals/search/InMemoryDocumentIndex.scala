package scala.meta.metals.search

import java.util
import java.util.concurrent.ConcurrentHashMap
import scala.meta.metals.Uri
import com.typesafe.scalalogging.LazyLogging
import scala.meta.internal.semanticdb3.SymbolInformation
import scala.meta.internal.semanticdb3.TextDocument

class InMemoryDocumentIndex(
    documents: util.Map[Uri, TextDocument] = new ConcurrentHashMap(),
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

class SymbolInformationsBySymbol(infos: util.HashMap[String, SymbolInformation])
    extends Seq[SymbolInformation] {
  def lookupSymbol(symbol: String): Option[SymbolInformation] =
    Option(infos.get(symbol))
  import scala.collection.JavaConverters._
  override def length: Int = infos.size()
  override def iterator: Iterator[SymbolInformation] =
    infos.values().iterator().asScala
  override def apply(idx: Int): SymbolInformation =
    throw new UnsupportedOperationException("SymbolInformationsBySymbol.apply")
}
