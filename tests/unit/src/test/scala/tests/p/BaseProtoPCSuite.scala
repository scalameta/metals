package tests.p

import scala.meta.internal.metals.Configs.ProtobufLspConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration

import tests.BaseLspSuite
import tests.BuildInfo

abstract class BaseProtoPCSuite(name: String) extends BaseLspSuite(name) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      protobufLspConfig = ProtobufLspConfig.enabled,
      referenceProvider = ReferenceProviderConfig.mbt,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
    )

  override def initializeGitRepo: Boolean = true
}
