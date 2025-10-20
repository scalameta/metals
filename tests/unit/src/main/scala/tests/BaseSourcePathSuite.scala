package tests
import scala.meta.internal.metals.Configs.CompilersConfig
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.pc.SourcePathMode

trait BaseSourcePathSuite extends BaseLspSuite {
  override def userConfig: UserConfiguration =
    UserConfiguration(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
    )

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      compilers = CompilersConfig().copy(
        sourcePathMode = SourcePathMode.PRUNED
      )
    )
}
