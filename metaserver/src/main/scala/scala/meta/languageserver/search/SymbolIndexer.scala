package scala.meta.languageserver.search

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.collection.concurrent.TrieMap
import scala.meta.languageserver.index._
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.semanticdb.Symbol

/**
 * Fast, compact, incremental, parallel and persistable search index for symbols.
 *
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
class SymbolIndexer(
    symbols: TrieMap[String, AtomicReference[SymbolData]] = TrieMap.empty
) extends LazyLogging { self =>

  /** Lookup scala.meta.Symbol */
  def get(symbol: Symbol): Option[SymbolData] = symbol match {
    case Symbol.Multi(ss) => ss.collectFirst { case self(i) => i }
    case s: Symbol => get(s.syntax)
  }

  /** Lookup symbol by its syntax. */
  def get(symbol: String): Option[SymbolData] =
    symbols
      .get(symbol)
      .map(_.get)
      .filter { s =>
        if (s.definition.isEmpty) {
          logger.info(s"Skipping symbol ${s.symbol}, has no definition")
        }
        s.definition.isDefined
      }

  /** Lookup symbol from inside a pattern match */
  def unapply(arg: Any): Option[SymbolData] = arg match {
    case s: String => get(s)
    case s: Symbol => get(s)
    case _ => None
  }

  /** Iterator for all indexed symbols */
  def allSymbols: Traversable[SymbolData] = new Traversable[SymbolData] {
    override def foreach[U](f: SymbolData => U): Unit =
      symbols.values.foreach(s => f(s.get))
  }

  /** Register the definition of a symbol at a given position.
   *
   * Overrides existing registered definition.
   */
  def addDefinition(
      symbol: String,
      position: Position
  ): Unit = updated(symbol) { index =>
    // NOTE(olafur): Here we override the previous definition, in some cases,
    // we should accummulate them, for example non-pure JS/JVM/Native projects.
    index.copy(definition = Some(position))
  }

  /**
   * Register metadata about a symbol.
   *
   * @param flags the modifiers of this symbol, see [[org.langmeta.semanticdb.HasFlags]]
   * @param name the name of the symbol, example "get" for scala.Option.get
   * @param signature the type signature of this symbol, example "List[T]" for List.tail
   */
  def addDenotation(
      symbol: String,
      flags: Long,
      name: String,
      signature: String
  ): Unit = updated(symbol) { index =>
    index.copy(flags = flags, signature = signature, name = name)
  }

  /**
   * Reguster a reference/call-site to this symbol.
   *
   * @param filename must be URI, can either be file on local disk or entry
   *                 in jar/zip.
   * @param range start/end offset where this symbol is referenced.
   * @param symbol
   */
  def addReference(
      filename: String, // TODO(olafur) change to java.net.URI?
      range: Range,
      symbol: String // TODO(olafur) move to first argument?
  ): Unit = updated(symbol) { index =>
    val ranges = index.references.getOrElse(filename, Ranges())
    val newRanges = ranges.addRanges(range)
    val newReferences = index.references + (filename -> newRanges)
    index.copy(references = newReferences)
  }

  private def newValue(symbol: String) =
    new AtomicReference(SymbolData(symbol = symbol))
  private def updated(symbol: String)(f: SymbolData => SymbolData): Unit = {
    val value = symbols.getOrElseUpdate(symbol, newValue(symbol))
    value.getAndUpdate(new UnaryOperator[SymbolData] {
      override def apply(index: SymbolData): SymbolData =
        f(index)
    })
  }

}
