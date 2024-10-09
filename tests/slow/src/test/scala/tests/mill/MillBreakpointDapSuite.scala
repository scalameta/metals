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

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(debugServerStartTimeout = 360)
}
