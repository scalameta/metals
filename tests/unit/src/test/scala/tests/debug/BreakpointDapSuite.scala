package tests.debug

import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class BreakpointDapSuite
    extends BaseBreakpointDapSuite(
      "debug-breakpoint",
      QuickBuildInitializer,
      QuickBuildLayout
    )
