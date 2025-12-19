package scala.meta.internal.metals.mbt

import scala.concurrent.Future

import scala.meta.infra.MonitoringClient
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.mtags.Mtags
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

/**
 * An mbt symbol search that lazily delegates to v1 or v2 based on the user config.
 *
 * This class only exists because the user config loads after initialization so
 * we need to delegate `onReindex` and other methods to correct implementation
 * as late as possible.
 */
class LazyMbtWorkspaceSymbolSearch(
    workspace: AbsolutePath,
    config: () => WorkspaceSymbolProviderConfig,
    statistics: () => StatisticsConfig,
    metrics: MonitoringClient,
    mtags: () => Mtags,
    mbt2: MbtV2WorkspaceSymbolSearch,
) extends MbtWorkspaceSymbolSearch {
  private lazy val mbt1 = new MbtWorkspaceSymbolProvider(
    gitWorkspace = workspace,
    config = config,
    statistics = statistics,
    mtags = mtags,
    metrics = metrics,
  )
  private def delegate: MbtWorkspaceSymbolSearch =
    if (config().isMBT1) {
      mbt1
    } else if (config().isMBT2) {
      mbt2
    } else {
      EmptyMbtWorkspaceSymbolSearch
    }

  override def possibleReferences(
      params: MbtPossibleReferencesParams
  ): Iterable[AbsolutePath] = delegate.possibleReferences(params)
  override def onReindex(): IndexingStats =
    delegate.onReindex()
  override def onDidChange(file: AbsolutePath): Future[Unit] =
    delegate.onDidChange(file)
  override def onDidChangeSymbols(
      params: OnDidChangeSymbolsParams
  ): Future[Unit] =
    delegate.onDidChangeSymbols(params)
  override def onDidDelete(file: AbsolutePath): Future[Unit] =
    delegate.onDidDelete(file)
  override def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = delegate.workspaceSymbolSearch(params, visitor)
  override def close(): Unit = delegate.close()
}
