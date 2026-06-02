package tests.mbt

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration
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
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

}
