package tests.mill

import tests.MillBuildLayout
import tests.MillServerInitializer
import tests.debug.BaseBreakpointDapSuite

class MillBreakpointDapSuite
    extends BaseBreakpointDapSuite(
      "mill-debug-breakpoint",
      MillServerInitializer,
      MillBuildLayout,
    )
