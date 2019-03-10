package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.util.Optional
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

/**
 * Implementation of SymbolSearch that delegates to WorkspaceSymbolProvider and SymbolDocumentationIndexer.
 */
class MetalsSymbolSearch(
    docs: Docstrings,
    wsp: WorkspaceSymbolProvider
) extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    docs.documentation(symbol)

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
