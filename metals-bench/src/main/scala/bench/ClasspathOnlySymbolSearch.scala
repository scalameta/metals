package bench

import java.util.Optional
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

/**
 * Implementation of `SymbolSearch` for only classpath symbols.
 *
 * Only used for benchmarking purposes.
 */
class ClasspathOnlySymbolSearch(classpath: ClasspathSearch)
    extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    Optional.empty()

  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    classpath.search(WorkspaceSymbolQuery.exact(query), visitor)
  }
}
