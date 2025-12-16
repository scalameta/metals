package scala.meta.internal.metals.mbt

import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

object EmptyMbtWorkspaceSymbolSearch extends MbtWorkspaceSymbolSearch {
  override def onReindex(): IndexingStats =
    IndexingStats.empty
  override def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = SymbolSearch.Result.COMPLETE
  def onDidChange(file: AbsolutePath): Unit = ()
  def onDidDelete(file: AbsolutePath): Unit = ()
  def onDidChangeSymbols(params: OnDidChangeSymbolsParams): Unit = ()
  override def close(): Unit = ()
}
