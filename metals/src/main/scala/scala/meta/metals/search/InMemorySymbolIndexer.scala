package scala.meta.metals.search

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.collection.concurrent.TrieMap
import scala.meta.metals.index.SymbolData
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.lsp.Location
import org.langmeta.lsp.Range
import org.langmeta.semanticdb.Symbol
import scala.meta.internal.semanticdb3
import scala.meta.metals.Uri

class InMemorySymbolIndexer(
    // simplest thing I could think of to get something off the ground.
    // we may want to consider using a proper key/value store instead.
    symbols: collection.concurrent.Map[String, AtomicReference[SymbolData]] =
      TrieMap.empty
) extends SymbolIndexer
    with LazyLogging { self =>

  override def get(symbol: Symbol): Option[SymbolData] = symbol match {
    case Symbol.Multi(ss) => ss.collectFirst { case self(i) => i }
    case s: Symbol => get(s.syntax)
  }

  override def get(symbol: String): Option[SymbolData] =
    symbols
      .get(symbol)
      .map(_.get)

  override def unapply(arg: Any): Option[SymbolData] = arg match {
    case s: String => get(s)
    case s: Symbol => get(s)
    case _ => None
  }

  override def allSymbols: Traversable[SymbolData] =
    new Traversable[SymbolData] {
      override def foreach[U](f: SymbolData => U): Unit =
        symbols.values.foreach(s => f(s.get))
    }

  override def addDefinition(
      symbol: String,
      position: Location
  ): Unit = updated(symbol) { index =>
    // NOTE(olafur): Here we override the previous definition, in some cases,
    // we should accummulate them, for example non-pure JS/JVM/Native projects.
    index.copy(definition = Some(position))
  }

  override def addSymbolInformation(
      info: semanticdb3.SymbolInformation
  ): Unit = updated(info.symbol) { index =>
    require(!info.kind.isLocal, "Local symbols should not be globally indexed!")
    index.copy(info = Some(info))
  }

  override def addReference(
      uri: Uri,
      range: Range,
      symbol: String
  ): Unit = updated(symbol) { index =>
    require(
      !symbol.startsWith("local"),
      s"Can't index local symbol '$symbol' in uri $uri"
    )
    val ranges = index.references.getOrElse(uri, Nil)
    val newRanges = range +: ranges
    val newReferences = index.references.updated(uri, newRanges)
    index.copy(references = newReferences)
  }

  private def newValue(symbol: String) = {
    require(!symbol.startsWith("local"), symbol)
    new AtomicReference(
      SymbolData(
        symbol = symbol,
        definition = None,
        references = Map.empty,
        info = None
      )
    )
  }

  private def updated(symbol: String)(f: SymbolData => SymbolData): Unit = {
    val value = symbols.getOrElseUpdate(symbol, newValue(symbol))
    value.getAndUpdate(new UnaryOperator[SymbolData] {
      override def apply(index: SymbolData): SymbolData =
        f(index)
    })
  }

}
