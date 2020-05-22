package tests

import scala.concurrent.ExecutionContext.Implicits.global

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CompressedPackageIndex
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath

object TestingWorkspaceSymbolProvider {
  def apply(
      workspace: AbsolutePath,
      buildTargets: BuildTargets = new BuildTargets,
      statistics: StatisticsConfig = StatisticsConfig.default,
      index: OnDemandSymbolIndex = OnDemandSymbolIndex(),
      bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
  ): WorkspaceSymbolProvider = {
    new WorkspaceSymbolProvider(
      workspace = workspace,
      statistics = statistics,
      buildTargets = new BuildTargets,
      index = index,
      _.toFileOnDisk(workspace),
      bucketSize = bucketSize
    )
  }
}
