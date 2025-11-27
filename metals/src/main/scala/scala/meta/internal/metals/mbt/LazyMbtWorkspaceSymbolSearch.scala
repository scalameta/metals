package scala.meta.internal.metals.mbt

import java.nio.file.Path
import java.{util => ju}

import scala.concurrent.ExecutionContext

import scala.meta.infra.MonitoringClient
import scala.meta.internal.metals.BaseWorkDoneProgress
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.TimerProvider
import scala.meta.internal.mtags.Mtags
import scala.meta.io.AbsolutePath
import scala.meta.pc.SemanticdbCompilationUnit
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
    buffers: Buffers,
    time: Time,
    metrics: MonitoringClient,
    timerProvider: TimerProvider,
    mtags: () => Mtags,
    progress: BaseWorkDoneProgress,
)(implicit ec: ExecutionContext)
    extends MbtWorkspaceSymbolSearch {
  private lazy val mbt1 = new MbtWorkspaceSymbolProvider(
    gitWorkspace = workspace,
    config = config,
    statistics = statistics,
    mtags = mtags,
    metrics = metrics,
    buffers = buffers,
    timerProvider = timerProvider,
  )
  private lazy val mbt2 = new MbtV2WorkspaceSymbolSearch(
    workspace = workspace,
    config = config,
    buffers = buffers,
    time = time,
    metrics = metrics,
    mtags = mtags,
    progress = progress,
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
  override def listPackage(pkg: String): ju.List[SemanticdbCompilationUnit] =
    delegate.listPackage(pkg)
  override def listAllPackages(): ju.Map[String, ju.Set[Path]] =
    delegate.listAllPackages()
  override def onReindex(): IndexingStats =
    delegate.onReindex()
  override def onDidChange(file: AbsolutePath): Unit =
    delegate.onDidChange(file)
  override def onDidChangeSymbols(params: OnDidChangeSymbolsParams): Unit =
    delegate.onDidChangeSymbols(params)
  override def onDidDelete(file: AbsolutePath): Unit =
    delegate.onDidDelete(file)
  override def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = delegate.workspaceSymbolSearch(params, visitor)
  override def close(): Unit = delegate.close()
}
