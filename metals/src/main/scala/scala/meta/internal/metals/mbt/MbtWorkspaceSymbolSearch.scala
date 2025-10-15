package scala.meta.internal.metals.mbt

import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

case class MbtWorkspaceSymbolSearchParams(
    query: String,
    buildTargetIdentifier: String,
)

trait MbtWorkspaceSymbolSearch {
  def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result
}

object EmptyMbtWorkspaceSymbolSearch extends MbtWorkspaceSymbolSearch {
  override def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = SymbolSearch.Result.COMPLETE
}
