package tests.mill

import scala.meta.internal.metals.MetalsServerConfig

import tests.MillBuildLayout
import tests.MillServerInitializer
import tests.debug.BaseBreakpointDapSuite

class MillBreakpointDapSuite
    extends BaseBreakpointDapSuite(
      "mill-debug-breakpoint",
      MillServerInitializer,
      MillBuildLayout,
    ) {

  // mill sometimes hangs and doesn't return main classes
  override protected val retryTimes: Int = 2

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(debugServerStartTimeout = 120)
}
