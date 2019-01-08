package tests

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

object TestingDiagnostics {
  def apply(
      workspace: AbsolutePath = PathIO.workingDirectory,
      buildTargets: BuildTargets = new BuildTargets(),
      buffers: Buffers = Buffers()
  ): Diagnostics = {
    new Diagnostics(
      buildTargets,
      buffers,
      new TestingClient(workspace, buffers),
      StatisticsConfig.default,
      () => UserConfiguration()
    )
  }
}
