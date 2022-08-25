package scala.meta.internal.metals

import java.util
import java.util.Collections

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location

case class DefinitionResult(
    locations: util.List[Location],
    symbol: String,
    definition: Option[AbsolutePath],
    semanticdb: Option[TextDocument],
) {
  def isEmpty: Boolean = locations.isEmpty()
  def ++(other: DefinitionResult) = DefinitionResult(
    (locations.asScala ++ other.locations.asScala).asJava,
    symbol,
    definition,
    semanticdb,
  )
}

object DefinitionResult {
  def empty(symbol: String): DefinitionResult =
    DefinitionResult(Collections.emptyList(), symbol, None, None)
  def empty: DefinitionResult = empty(Symbols.None)
}
