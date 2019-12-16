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
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * Implementation of SymbolSearch that delegates to WorkspaceSymbolProvider and SymbolDocumentationIndexer.
 */
class MetalsSymbolSearch(
    docs: Docstrings,
    wsp: WorkspaceSymbolProvider,
    defn: DefinitionProvider
) extends SymbolSearch {
  // A cache for definitionSourceToplevels.
  // The key is an absolutepath to the dependency source file, and
  // the value is the list of symbols that the file contains.
  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()

  def reset(): Unit = {
    dependencySourceCache.clear()
  }

  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    docs.documentation(symbol)

  def definition(symbol: String): ju.List[Location] = {
    defn.fromSymbol(symbol)
  }

  /**
   * Returns a list of semanticdb symbols in a source file that contains the
   * definition of the given symbol.
   */
  override def definitionSourceToplevels(symbol: String): ju.List[String] = {
    defn
      .definitionPathInputFromSymbol(symbol)
      .map(input => {
        val path = AbsolutePath(input.path)
        if (path.isWorkspaceSource(wsp.workspace)) {
          // If the source file is a workspace source, retrieve its symbols from
          // WorkspaceSymbolProvider so that metals server can reuse its cache.
          wsp.inWorkspace
            .get(path.toNIO)
            .map(symInfo => {
              symInfo.symbols
                .sortBy(sym =>
                  (
                    sym.range.getStart().getLine(),
                    sym.range.getStart().getCharacter()
                  )
                )
                .map(_.symbol)
                .asJava
            })
            .getOrElse(
              ju.Collections.emptyList[String]()
            )
        } else {
          dependencySourceCache.getOrElseUpdate(
            path,
            Mtags.toplevels(input).asJava
          )
        }
      })
      .getOrElse(ju.Collections.emptyList())
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
