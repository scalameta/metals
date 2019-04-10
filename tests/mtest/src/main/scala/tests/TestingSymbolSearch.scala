package tests

import java.{util => ju}
import org.eclipse.lsp4j.Location
import java.util.Optional
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.internal.mtags.OnDemandSymbolIndex

/**
 * Implementation of `SymbolSearch` for testing purposes.
 *
 * We can't use `MetalsSymbolSearch` because it relies on WorkspaceSymbolProvider, which is 2.12 only.
 */
class TestingSymbolSearch(
    classpath: ClasspathSearch = ClasspathSearch.empty,
    docs: Docstrings = Docstrings.empty,
    workspace: TestingWorkspaceSearch = TestingWorkspaceSearch.empty,
    index: OnDemandSymbolIndex = OnDemandSymbolIndex()
) extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] = {
    docs.documentation(symbol)
  }

  import scala.meta.internal.mtags.Symbol
  override def definition(symbol: String): ju.List[Location] = {
    index.definition(Symbol(symbol)) match {
      case None =>
        ju.Collections.emptyList()
      case Some(value) =>
        import org.eclipse.lsp4j.Range
        import org.eclipse.lsp4j.Position
        val filename = value.path.toNIO.getFileName().toString()
        val uri = s"$symbol $filename"
        ju.Collections.singletonList(
          new Location(
            uri,
            new Range(new Position(0, 0), new Position(0, 0))
          )
        )
    }
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
