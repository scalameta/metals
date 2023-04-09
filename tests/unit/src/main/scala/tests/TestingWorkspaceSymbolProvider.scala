package tests

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CompressedPackageIndex
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath

object TestingWorkspaceSymbolProvider {
  def apply(
      workspace: AbsolutePath,
      saveClassFileToDisk: Boolean = true,
      index: OnDemandSymbolIndex = OnDemandSymbolIndex.empty(),
      bucketSize: Int = CompressedPackageIndex.DefaultBucketSize,
  ): WorkspaceSymbolProvider = {
    new WorkspaceSymbolProvider(
      workspace = workspace,
      buildTargets = BuildTargets.empty,
      index = index,
      saveClassFileToDisk = saveClassFileToDisk,
      () => ExcludedPackagesHandler.default,
      bucketSize = bucketSize,
    )
  }
}
