package tests
import scala.meta.internal.metals.Configs.CompilersConfig
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.pc.SourcePathMode

class OutlineLspSuite extends BaseNonCompilingLspSuite("outline") {
  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      compilers = CompilersConfig().copy(
        sourcePathMode = SourcePathMode.PRUNED
      )
    )

  override val scalaVersionConfig: String = ""
  override val scalaVersion: String = V.scala213
  override val saveAfterChanges: Boolean = false
  override val scala3Diagnostics = false
}
