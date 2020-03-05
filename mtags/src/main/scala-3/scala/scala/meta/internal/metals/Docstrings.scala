package scala.meta.internal.metals

import java.util.Optional
import java.util.logging.Level
import java.util.logging.Logger
import scala.collection.concurrent.TrieMap
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.Language
import scala.meta.pc.SymbolDocumentation
import scala.util.control.NonFatal
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.inputs.Input
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

/**
 * Implementation of the `documentation(symbol: String): Option[SymbolDocumentation]` method in `SymbolSearch`.
 *
 * Handles both javadoc and scaladoc. TODO Not implemented for Scala 3
 */
class Docstrings(index: GlobalSymbolIndex) {
  val cache = new TrieMap[String, SymbolDocumentation]()
  private val logger = Logger.getLogger(classOf[Docstrings].getName)

  def documentation(symbol: String): Optional[SymbolDocumentation] =
    Optional.empty

  def expireSymbolDefinition(path: AbsolutePath): Unit = {}

  private def cacheSymbol(doc: SymbolDocumentation): Unit = {}

  private def indexSymbol(symbol: String): Unit = {}

  private def indexSymbolDefinition(defn: SymbolDefinition): Unit = {}

}

object Docstrings {
  def empty: Docstrings = new Docstrings(null)
}
