package scala.meta.internal.metals

import java.util.Optional
import java.util.logging.Level
import java.util.logging.Logger

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.ScalaMtags
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.io.AbsolutePath
import scala.meta.pc.ParentSymbols
import scala.meta.pc.ReportContext
import scala.meta.pc.SymbolDocumentation

/**
 * Implementation of the `documentation(symbol: String): Option[SymbolDocumentation]` method in `SymbolSearch`.
 *
 * Handles both javadoc and scaladoc.
 */
class Docstrings(index: GlobalSymbolIndex) {
  val cache = new TrieMap[String, SymbolDocumentation]()
  private val logger = Logger.getLogger(classOf[Docstrings].getName)

  def documentation(
      symbol: String,
      parents: ParentSymbols
  ): Optional[SymbolDocumentation] = {
    val result = cache.get(symbol) match {
      case Some(value) =>
        if (value == EmptySymbolDocumentation) None
        else Some(value)
      case None =>
        indexSymbol(symbol)
        val result = cache.get(symbol)
        if (result.isEmpty)
          cache(symbol) = EmptySymbolDocumentation
        result
    }
    /* Fall back to parent javadocs/scaladocs if nothing is specified for the current symbol
     * This way we also cache the result in order not to calculate parents again.
     */
    val resultWithParentDocs = result match {
      case Some(value: MetalsSymbolDocumentation)
          if value.docstring.isEmpty() =>
        Some(parentDocumentation(symbol, value, parents))
      case None =>
        Some(
          parentDocumentation(
            symbol,
            MetalsSymbolDocumentation.empty(symbol),
            parents
          )
        )
      case _ => result
    }
    Optional.ofNullable(resultWithParentDocs.orNull)
  }

  def parentDocumentation(
      symbol: String,
      docs: MetalsSymbolDocumentation,
      parents: ParentSymbols
  ): SymbolDocumentation = {
    parents
      .parents()
      .asScala
      .flatMap { s =>
        if (cache.contains(s)) cache.get(s)
        else {
          indexSymbol(s)
          cache.get(s)
        }
      }
      .find(_.docstring().nonEmpty)
      .fold {
        docs
      } { withDocs =>
        val updated = docs.copy(docstring = withDocs.docstring())
        cache(symbol) = updated
        updated
      }
  }

  /**
   * Expire all symbols showed in the given scala source file.
   *
   * Note that what this method does is only expiring the cache, and
   * it doesn't update the cache in honor of the memory footprint.
   * Otherwise, if we update the cache for symbols every time we save a file,
   * metals will cache all symbols in files we've saved, and it consumes a considerable amount of memory.
   *
   * @param path the absolute path for the source file to update.
   */
  def expireSymbolDefinition(path: AbsolutePath, dialect: Dialect): Unit = {
    path.toLanguage match {
      case Language.SCALA =>
        new Deindexer(path.toInput, dialect).indexRoot()
      case _ =>
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
          .foreach(defn.path.toInput, defn.dialect)(cacheSymbol)
      case _ =>
    }
  }

  private class Deindexer(
      input: Input.VirtualFile,
      dialect: Dialect
  ) extends ScalaMtags(input, dialect) {
    override def visitOccurrence(
        occ: SymbolOccurrence,
        sinfo: SymbolInformation,
        owner: String
    ): Unit = {
      cache.remove(occ.symbol)
    }
  }

}

object Docstrings {
  def empty(implicit rc: ReportContext): Docstrings = new Docstrings(
    OnDemandSymbolIndex.empty()
  )
}
