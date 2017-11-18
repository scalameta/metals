package scala.meta.languageserver

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.meta.languageserver.index.Position
import scala.meta.languageserver.index.Ranges
import scala.meta.languageserver.index.Range
import scala.meta.languageserver.index.SymbolIndex
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.semanticdb.Symbol
import scala.collection.concurrent.TrieMap

class SymbolIndexerMap(
    symbols: TrieMap[String, AtomicReference[SymbolIndex]] = TrieMap.empty
) extends LazyLogging { self =>
  private def newValue(symbol: String) = {
    new AtomicReference(SymbolIndex(symbol = symbol))
  }

  def index: Traversable[SymbolIndex] = new Traversable[SymbolIndex] {
    override def foreach[U](f: SymbolIndex => U): Unit =
      symbols.values.foreach(s => f(s.get))
  }

  def updated(symbol: String)(f: SymbolIndex => SymbolIndex): Unit = {
    val value = symbols.getOrElseUpdate(symbol, newValue(symbol))
    value.getAndUpdate(new UnaryOperator[SymbolIndex] {
      override def apply(index: SymbolIndex): SymbolIndex =
        f(index)
    })
  }

  def debug(): Unit = {
    symbols.keys.foreach(value => logger.info(s"[symbolmap] $value"))
  }

  def get(symbol: Symbol): Option[SymbolIndex] = symbol match {
    case Symbol.Multi(ss) => ss.collectFirst { case self(i) => i }
    case s: Symbol => get(s.syntax)
  }
  def get(symbol: String): Option[SymbolIndex] =
    symbols.get(symbol).map(_.get).filter(_.definition.isDefined)
  def unapply(arg: Any): Option[SymbolIndex] = arg match {
    case s: String => get(s)
    case s: Symbol => get(s)
    case _ => None
  }

  def addDefinition(
      symbol: String,
      position: Position
  ): Unit = updated(symbol) { index =>
    index.definition match {
      case Some(_) =>
        // Do nothing, conflicting symbol definitions, for example js/jvm
        index
      case _ =>
        index.copy(definition = Some(position))
    }
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
