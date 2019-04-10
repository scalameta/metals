package scala.meta.internal.metals

import java.{util => ju}
import org.eclipse.lsp4j.Location
import java.util.Optional
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

/**
 * Implementation of SymbolSearch that delegates to WorkspaceSymbolProvider and SymbolDocumentationIndexer.
 */
class MetalsSymbolSearch(
    docs: Docstrings,
    wsp: WorkspaceSymbolProvider,
    defn: DefinitionProvider
) extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    docs.documentation(symbol)

  def definition(symbol: String): ju.List[Location] = {
    defn.fromSymbol(symbol)
  }

  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    wsp.search(
      WorkspaceSymbolQuery.exact(query),
      visitor,
      Some(new BuildTargetIdentifier(buildTargetIdentifier))
    )
  }
}
