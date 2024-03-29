package scala.meta.internal.metals

import java.net.URI
import java.util.Optional
import java.{util => ju}

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.io.AbsolutePath
import scala.meta.pc.ParentSymbols
import scala.meta.pc.ReportContext
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Location

/**
 * Implementation of SymbolSearch that delegates to WorkspaceSymbolProvider and SymbolDocumentationIndexer.
 */
class MetalsSymbolSearch(
    docs: Docstrings,
    wsp: WorkspaceSymbolProvider,
    defn: DefinitionProvider,
)(implicit rc: ReportContext)
    extends SymbolSearch {
  // A cache for definitionSourceToplevels.
  // The key is an absolute path to the dependency source file, and
  // the value is the list of symbols that the file contains.
  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()

  def reset(): Unit = {
    dependencySourceCache.clear()
  }

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
  ): Optional[SymbolDocumentation] =
    docs.documentation(symbol, parents)

  def definition(symbol: String, source: URI): ju.List[Location] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    defn.fromSymbol(symbol, sourcePath)
  }

  /**
   * Returns a list of semanticdb symbols in a source file that contains the
   * definition of the given symbol.
   */
  override def definitionSourceToplevels(
      symbol: String,
      source: URI,
  ): ju.List[String] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    defn
      .definitionPathInputFromSymbol(symbol, sourcePath)
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
                    sym.range.getStart().getCharacter(),
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
            Mtags.topLevelSymbols(path).asJava,
          )
        }
      })
      .getOrElse(ju.Collections.emptyList())
  }

  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    def search(query: WorkspaceSymbolQuery) =
      wsp.search(
        query,
        visitor,
        Some(new BuildTargetIdentifier(buildTargetIdentifier)),
      )

    val wQuery = WorkspaceSymbolQuery.exact(query)
    val (result, count) = search(wQuery)
    if (wQuery.isShortQuery && count == 0)
      search(WorkspaceSymbolQuery.exact(query, isShortQueryRetry = true))._1
    else result
  }

  override def searchMethods(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    wsp.searchMethods(
      query,
      visitor,
      Some(new BuildTargetIdentifier(buildTargetIdentifier)),
    )
  }
}
