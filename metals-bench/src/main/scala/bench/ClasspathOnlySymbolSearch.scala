package bench

import java.{util => ju}
import java.util.Optional
import org.eclipse.lsp4j.Location
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.pc.VirtualFile

/**
 * Implementation of `SymbolSearch` for only classpath symbols.
 *
 * Only used for benchmarking purposes.
 */
class ClasspathOnlySymbolSearch(classpath: ClasspathSearch)
    extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    Optional.empty()

  def definition(symbol: String): ju.List[Location] = ju.Collections.emptyList()

  override def definitionSource(symbol: String): ju.Optional[VirtualFile] =
    ju.Optional.empty()

  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    classpath.search(WorkspaceSymbolQuery.exact(query), visitor)
  }
}
