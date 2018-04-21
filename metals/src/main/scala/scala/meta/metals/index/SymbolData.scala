package scala.meta.metals.index

import org.langmeta.{lsp => l}
import scala.meta.metals.Uri
import scala.meta.internal.{semanticdb3 => s}

/** All metadata about a single symbol that is stored in the symbol index.
 *
 * @param symbol The symbol itself.
 * @param definition The location where this symbol is defined, could come from project
 *                   sources via metac or dependency sources via mtags.
 * @param references Locations of references to this symbol, come from project
 *                   sources via metac.
 * @param info The SemanticDB information about this symbol, contains type, owner, name,
 *             accessibility, kind, and other juicy metadata.
 */
case class SymbolData(
    symbol: String,
    definition: Option[l.Location],
    references: Map[Uri, Seq[l.Range]],
    info: Option[s.SymbolInformation]
)
