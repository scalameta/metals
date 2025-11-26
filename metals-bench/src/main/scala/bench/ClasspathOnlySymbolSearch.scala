package bench

import java.net.URI
import java.util.Optional
import java.util as ju
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.{ContentType, ParentSymbols, SymbolDocumentation, SymbolSearch, SymbolSearchVisitor, ToplevelMemberKind}
import org.eclipse.lsp4j.Location

/**
 * Implementation of `SymbolSearch` for only classpath symbols.
 *
 * Only used for benchmarking purposes.
 */
class ClasspathOnlySymbolSearch(classpath: ClasspathSearch)
    extends SymbolSearch {
  override def documentation(
      symbol: String,
      parents: ParentSymbols,
  ): Optional[SymbolDocumentation] =
    Optional.empty()

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
      docstringContentType: ContentType,
  ): Optional[SymbolDocumentation] = Optional.empty()

  def definition(symbol: String, source: URI): ju.List[Location] =
    ju.Collections.emptyList()

  override def definitionSourceToplevels(
      symbol: String,
      source: URI,
  ): ju.List[String] =
    ju.Collections.emptyList()

  override def search(
      query: String,
      buildTargetIdentifier: String,
      kind: ju.Optional[ToplevelMemberKind],
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    classpath.search(WorkspaceSymbolQuery.exact(query), visitor)._1
  }

  override def searchMethods(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }
}
