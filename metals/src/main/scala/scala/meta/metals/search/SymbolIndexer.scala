package scala.meta.metals.search

import org.langmeta.lsp.Location
import org.langmeta.lsp.Range
import scala.meta.metals.index._
import org.langmeta.semanticdb.Symbol
import scala.meta.internal.semanticdb3
import scala.meta.metals.Uri

/**
 * A key/value store with String keys (by symbol syntax) and
 * SymbolData as values.
 *
 * A good implementation of this trait should be:
 * - Fast: lookups should be instant to be useful from the editor.
 * - Compact: memory footprint should be small to fit in-memory even for
 *            large corpora (>millions of loc) on commodity hardware (dev laptop).
 * - Incremental: can register references to a symbol without the symbol's
 *                definition, and vice-versa.
 * - Parallel: all updates are thread safe.
 * - Persistable: it's possible to dump this index to file, and load up later.
 *                (TODO(olafur) not yet implemented)
 * All of these features may not be fully implemented yet, but the plan is to
 * reach there eventually.
 *
 * It's possible to rebuild a [[scala.meta.Database]] from a SymbolIndexer with
 * [[InverseSymbolIndexer]].
 */
trait SymbolIndexer { self =>

  /** Lookup scala.meta.Symbol */
  def get(symbol: Symbol): Option[SymbolData]

  /** Lookup symbol by its syntax. */
  def get(symbol: String): Option[SymbolData]

  /** Lookup symbol from inside a pattern match */
  def unapply(arg: Any): Option[SymbolData] = arg match {
    case s: String => get(s)
    case s: Symbol => get(s)
    case _ => None
  }

  /** Iterator for all indexed symbols */
  def allSymbols: Traversable[SymbolData]

  /** Register the definition of a symbol at a given position.
   *
   * Overrides existing registered definition.
   */
  def addDefinition(
      symbol: String,
      position: Location
  ): Unit

  /**
   * Register metadata about a symbol.
   *
   * @param info The information about this symbol
   *             https://github.com/scalameta/scalameta/blob/master/semanticdb/semanticdb3/semanticdb3.md#symbolinformation
   */
  def addSymbolInformation(
      info: semanticdb3.SymbolInformation
  ): Unit

  /**
   * Reguster a reference/call-site to this symbol.
   *
   * @param uri must be URI, can either be file on local disk or entry
   *            in jar/zip.
   * @param range start/end offset where this symbol is referenced.
   * @param symbol
   */
  def addReference(
      uri: Uri,
      range: Range,
      symbol: String
  ): Unit
}
