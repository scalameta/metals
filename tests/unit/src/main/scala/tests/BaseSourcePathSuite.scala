package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.config.CompilersConfig
import scala.meta.internal.metals.config.FallbackClasspathConfig
import scala.meta.internal.metals.config.FallbackSourcepathConfig
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.pc.SourcePathMode

trait BaseSourcePathSuite extends BaseLspSuite {
  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      fallbackClasspath = FallbackClasspathConfig.All3rdparty,
      fallbackSourcepath = FallbackSourcepathConfig.AllSources,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.MBT,
      additionalPcChecks = List("refchecks"),
    )

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      compilers = CompilersConfig().copy(
        sourcePathMode = SourcePathMode.PRUNED
      )
    )
}
