package scala.meta.internal.metals

import java.{util => ju}
import org.eclipse.lsp4j.Location
import java.util.Optional
import scala.collection.concurrent.TrieMap
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.internal.mtags.Mtags

/**
 * Implementation of SymbolSearch that delegates to WorkspaceSymbolProvider and SymbolDocumentationIndexer.
 */
class MetalsSymbolSearch(
    docs: Docstrings,
    wsp: WorkspaceSymbolProvider,
    defn: DefinitionProvider
) extends SymbolSearch {
  // cache for definitionSourceTopLevels
  private val cache = new TrieMap[String, ju.List[String]]()

  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    docs.documentation(symbol)

  def definition(symbol: String): ju.List[Location] = {
    defn.fromSymbol(symbol)
  }

  override def definitionSourceToplevels(symbol: String): ju.List[String] = {
    import scala.collection.JavaConverters._
    cache.getOrElseUpdate(
      symbol,
      defn
        .definitionPathInputFromSymbol(symbol)
        .map(input => Mtags.toplevels(input).asJava)
        .getOrElse(ju.Collections.emptyList())
    )
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
