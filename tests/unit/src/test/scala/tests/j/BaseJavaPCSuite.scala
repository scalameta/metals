package tests.j

import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration

import tests.BaseLspSuite
import tests.BuildInfo

abstract class BaseJavaPCSuite(name: String) extends BaseLspSuite(name) {
  override def userConfig: UserConfiguration =
    UserConfiguration(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
    )

  override def initializeGitRepo: Boolean = true
}
