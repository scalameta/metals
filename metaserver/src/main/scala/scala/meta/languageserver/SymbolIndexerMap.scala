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
  private val mappingFunction: Function[String, AtomicReference[SymbolIndex]] =
    t => new AtomicReference(SymbolIndex(symbol = t))

  def addDefinition(symbol: String, position: Position): Unit = {
    val value = symbols.computeIfAbsent(symbol, mappingFunction)
    value.getAndUpdate(new UnaryOperator[SymbolIndex] {
      override def apply(t: SymbolIndex): SymbolIndex =
        t.definition.fold(t.copy(definition = Some(position))) { _ =>
          // Do nothing, conflicting symbol definitions, for example js/jvm
          t
        }
    })
  }

  def addReference(
      filename: String,
      range: Range,
      symbol: String
  ): Unit = {
    val value = symbols.computeIfAbsent(symbol, mappingFunction)
    value.getAndUpdate(new UnaryOperator[SymbolIndex] {
      override def apply(t: SymbolIndex): SymbolIndex = {
        val ranges = t.references.getOrElse(filename, Ranges())
        val newRanges = ranges.addRanges(range)
        val newReferences = t.references + (filename -> newRanges)
        t.copy(references = newReferences)
      }
    })
  }

}
