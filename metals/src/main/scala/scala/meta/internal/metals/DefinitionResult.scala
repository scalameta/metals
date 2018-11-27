package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.Location
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

case class DefinitionResult(
    locations: util.List[Location],
    symbol: String,
    definition: Option[AbsolutePath],
    semanticdb: Option[TextDocument]
)

object DefinitionResult {
  def empty(symbol: String): DefinitionResult =
    DefinitionResult(Collections.emptyList(), symbol, None, None)
  def empty: DefinitionResult = empty(Symbols.None)
}
