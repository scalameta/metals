package scala.meta.internal.metals.mbt

import scala.concurrent.Future

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
  def onDidChange(file: AbsolutePath): Future[Unit] = Future.unit
  def onDidDelete(file: AbsolutePath): Future[Unit] = Future.unit
  def onDidChangeSymbols(params: OnDidChangeSymbolsParams): Future[Unit] =
    Future.unit
  override def close(): Unit = ()
}
