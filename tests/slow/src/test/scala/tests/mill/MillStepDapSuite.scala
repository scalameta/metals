package tests.mill

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsServerConfig

import tests.MillBuildLayout
import tests.MillServerInitializer
import tests.debug.BaseStepDapSuite

class MillStepDapSuite
    extends BaseStepDapSuite(
      "mill-debug-step",
      MillServerInitializer,
      MillBuildLayout,
    ) {

  // mill sometimes hangs and doesn't return main classes
  override protected val retryTimes: Int = 2

  // otherwise we get both Scala 2.12 and 2.13 dependencies, which is more tricky for the tests
  override def scalaVersion: String = BuildInfo.scala212

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(debugServerStartTimeout = 360)
}
