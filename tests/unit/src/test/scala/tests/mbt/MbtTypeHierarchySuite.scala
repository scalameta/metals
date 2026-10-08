package tests.mbt

import scala.meta.internal.metals.config.AutoImportBuildKind
import scala.meta.internal.metals.config.ReferenceProviderConfig
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BuildInfo
import tests.TypeHierarchySpec

class MbtTypeHierarchySuite
    extends BaseMbtReferenceSuite("mbt-type-hierarchy")
    with TypeHierarchySpec {

  override def withMbt: Boolean = true

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.MBT,
      referenceProvider = ReferenceProviderConfig.MBT,
      preferredBuildServer = Some(MbtBuildServer.name),
      autoImportBuild = AutoImportBuildKind.All,
    )

}
