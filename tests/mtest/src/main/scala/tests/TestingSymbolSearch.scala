package tests

import java.util.Optional
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

/**
 * Implementation of `SymbolSearch` for testing purposes.
 *
 * We can't use `MetalsSymbolSearch` because it relies on WorkspaceSymbolProvider, which is 2.12 only.
 */
class TestingSymbolSearch(
    classpath: ClasspathSearch = ClasspathSearch.empty,
    docs: Docstrings = Docstrings.empty,
    workspace: TestingWorkspaceSearch = TestingWorkspaceSearch.empty
) extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] = {
    docs.documentation(symbol)
  }

  override def search(
      textQuery: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    val query = WorkspaceSymbolQuery.exact(textQuery)
    workspace.search(query, visitor)
    classpath.search(query, visitor)
  }
}
