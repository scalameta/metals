package scala.meta.internal.metals.mcp

import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.mcp.McpQueryEngine.kindToTypeString
import scala.meta.io.AbsolutePath

class McpSymbolProvider(
    scalaVersionSelector: ScalaVersionSelector,
    mcpSearch: McpSymbolSearch,
) {

  /**
   * Finds symbols matching the given fully qualified class name.
   *
   * @param fqcn fully qualified class name
   * @param fileInFocus optional path to the file in focus
   * @return a list of symbol search results
   */
  def symbols(
      fqcn: String,
      fileInFocus: Option[AbsolutePath],
  ): List[SymbolSearchResult] = {
    mcpSearch.exactSearch(fqcn, fileInFocus) match {
      case Seq() if fqcn.lastIndexOf('.') > 0 =>
        // There is a chance that the symbol is a method
        val owner = fqcn.substring(0, fqcn.lastIndexOf('.'))
        val foundMatching = new ListBuffer[SymbolSearchResult]()
        mcpSearch.exactSearch(owner, fileInFocus).foreach { symbol =>
          symbol.definitionPath match {
            case Some(path) =>
              SemanticdbDefinition.foreach(
                path.toInput,
                scalaVersionSelector.getDialect(path),
                includeMembers = true,
              ) { semanticdbDefn =>
                lazy val kind =
                  kindToTypeString(semanticdbDefn.info.kind.toLsp).getOrElse(
                    SymbolType.Unknown(semanticdbDefn.info.kind.toString)
                  )
                val fqcn = semanticdbDefn.info.symbol.fqcn
                val searchResult = SymbolSearchResult(
                  path = fqcn,
                  symbolType = kind,
                  symbol = semanticdbDefn.info.symbol,
                )
                foundMatching += searchResult
              }(EmptyReportContext)
            case None =>
          }
        }
        foundMatching.toList
      case results =>
        results.map(_.toMcpSymbolSearchResult).toList
    }
  }

}
