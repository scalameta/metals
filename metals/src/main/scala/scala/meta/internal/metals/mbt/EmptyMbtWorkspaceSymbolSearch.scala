package scala.meta.internal.metals.mbt

import java.nio.file.Path
import java.{util => ju}

import scala.meta.io.AbsolutePath
import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

object EmptyMbtWorkspaceSymbolSearch extends MbtWorkspaceSymbolSearch {
  override def listPackage(pkg: String): ju.List[SemanticdbCompilationUnit] =
    ju.Collections.emptyList()
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
  override def listAllPackages(): ju.Map[String, ju.Set[Path]] =
    ju.Collections.emptyMap()
}
