package tests.p

import scala.meta.internal.metals.config.ReferenceProviderConfig
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig

import tests.BaseLspSuite
import tests.BuildInfo

abstract class BaseProtoPCSuite(name: String) extends BaseLspSuite(name) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      protobufLspEnabled = true,
      referenceProvider = ReferenceProviderConfig.MBT,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.MBT,
    )

  override def initializeGitRepo: Boolean = true
}
