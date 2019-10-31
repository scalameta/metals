package scala.meta.internal.metals

import java.util.Optional
import java.util.logging.Level
import java.util.logging.Logger
import scala.collection.concurrent.TrieMap
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.Language
import scala.meta.pc.SymbolDocumentation
import scala.util.control.NonFatal
import scala.meta.internal.mtags.GlobalSymbolIndex

/**
 * Implementation of the `documentation(symbol: String): Option[SymbolDocumentation]` method in `SymbolSearch`.
 *
 * Handles both javadoc and scaladoc.
 */
class Docstrings(index: GlobalSymbolIndex) {
  val cache: TrieMap[String, SymbolDocumentation] =
    TrieMap.empty[String, SymbolDocumentation]
  private val logger = Logger.getLogger(classOf[Docstrings].getName)

  def documentation(symbol: String): Optional[SymbolDocumentation] = {
    cache.get(symbol) match {
      case Some(value) =>
        if (value == EmptySymbolDocumentation) Optional.empty()
        else Optional.of(value)
      case None =>
        indexSymbol(symbol)
        val result = cache.get(symbol)
        if (result.isEmpty) {
          cache(symbol) = EmptySymbolDocumentation
        }
        Optional.ofNullable(result.orNull)
    }
  }

  private def cacheSymbol(doc: SymbolDocumentation): Unit = {
    cache(doc.symbol()) = doc
  }

  private def indexSymbol(symbol: String): Unit = {
    index.definition(Symbol(symbol)) match {
      case Some(defn) =>
        try {
          indexSymbolDefinition(defn)
        } catch {
          case NonFatal(e) =>
            logger.log(Level.SEVERE, defn.path.toURI.toString, e)
        }
      case None =>
    }
  }

  private def indexSymbolDefinition(defn: SymbolDefinition): Unit = {
    defn.path.toLanguage match {
      case Language.JAVA =>
        JavadocIndexer
          .foreach(defn.path.toInput)(cacheSymbol)
      case Language.SCALA =>
        ScaladocIndexer
          .foreach(defn.path.toInput)(cacheSymbol)
      case _ =>
    }
  }

}

object Docstrings {
  def empty: Docstrings = new Docstrings(OnDemandSymbolIndex())
}
