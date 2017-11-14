package scala.meta.languageserver

import java.util
import java.util.function.Function
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.meta.languageserver.index.Position
import scala.meta.languageserver.index.Ranges
import scala.meta.languageserver.index.Range
import scala.meta.languageserver.index.SymbolIndex

class SymbolIndexerMap(
    symbols: util.Map[String, AtomicReference[SymbolIndex]] =
      new ConcurrentHashMap()
) {
  private val mappingFunction
    : Function[String, AtomicReference[SymbolIndex]] = { index =>
    new AtomicReference(SymbolIndex(symbol = index))
  }

  def updated(symbol: String)(f: SymbolIndex => SymbolIndex): Unit = {
    val value = symbols.computeIfAbsent(symbol, mappingFunction)
    value.getAndUpdate(new UnaryOperator[SymbolIndex] {
      override def apply(index: SymbolIndex): SymbolIndex =
        f(index)
    })
  }

  def get(symbol: String): Option[SymbolIndex] =
    Option(symbols.get(symbols)).map(_.get)

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
