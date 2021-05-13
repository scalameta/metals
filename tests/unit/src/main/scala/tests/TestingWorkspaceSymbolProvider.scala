package tests

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CompressedPackageIndex
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath

object TestingWorkspaceSymbolProvider {
  def apply(
      workspace: AbsolutePath,
      statistics: StatisticsConfig = StatisticsConfig.default,
      index: OnDemandSymbolIndex = OnDemandSymbolIndex.empty(),
      bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
  ): WorkspaceSymbolProvider = {
    new WorkspaceSymbolProvider(
      workspace = workspace,
      statistics = statistics,
      buildTargets = BuildTargets.withoutAmmonite,
      index = index,
      new ExcludedPackagesHandler().isExcludedPackage,
      bucketSize = bucketSize
    )
  }
}
