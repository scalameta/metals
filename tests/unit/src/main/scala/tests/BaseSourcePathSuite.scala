package tests
import scala.meta.internal.metals.Configs.CompilersConfig
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.pc.SourcePathMode

trait BaseSourcePathSuite extends BaseLspSuite {
  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      fallbackClasspath = FallbackClasspathConfig.all3rdparty,
    )

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      compilers = CompilersConfig().copy(
        sourcePathMode = SourcePathMode.PRUNED
      )
    )
}
