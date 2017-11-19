package scala.meta.languageserver.search

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.collection.concurrent.TrieMap
import scala.meta.languageserver.index._
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.semanticdb.Symbol

class SymbolIndexerMap(
    symbols: TrieMap[String, AtomicReference[SymbolData]] = TrieMap.empty
) extends LazyLogging { self =>
  private def newValue(symbol: String) = {
    new AtomicReference(SymbolData(symbol = symbol))
  }

  def allSymbols: Traversable[SymbolData] = new Traversable[SymbolData] {
    override def foreach[U](f: SymbolData => U): Unit =
      symbols.values.foreach(s => f(s.get))
  }

  def updated(symbol: String)(f: SymbolData => SymbolData): Unit = {
    val value = symbols.getOrElseUpdate(symbol, newValue(symbol))
    value.getAndUpdate(new UnaryOperator[SymbolData] {
      override def apply(index: SymbolData): SymbolData =
        f(index)
    })
  }

  def debug(): Unit = {
    symbols.keys.foreach(value => logger.info(s"[symbolmap] $value"))
  }

  def get(symbol: Symbol): Option[SymbolData] = symbol match {
    case Symbol.Multi(ss) => ss.collectFirst { case self(i) => i }
    case s: Symbol => get(s.syntax)
  }
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

  def unapply(arg: Any): Option[SymbolData] = arg match {
    case s: String => get(s)
    case s: Symbol => get(s)
    case _ => None
  }

  def addDefinition(
      symbol: String,
      position: Position
  ): Unit = updated(symbol) { index =>
    // NOTE(olafur): Here we override the previous definition, in some cases,
    // we should accummulate them, for example non-pure JS/JVM/Native projects.
    index.copy(definition = Some(position))
  }

  def addDenotation(
      symbol: String,
      flags: Long,
      name: String,
      signature: String
  ): Unit = updated(symbol) { index =>
    index.copy(flags = flags, signature = signature, name = name)
  }

  def addReference(
      filename: String,
      range: Range,
      symbol: String
  ): Unit = updated(symbol) { index =>
    val ranges = index.references.getOrElse(filename, Ranges())
    val newRanges = ranges.addRanges(range)
    val newReferences = index.references + (filename -> newRanges)
    index.copy(references = newReferences)
  }
}
