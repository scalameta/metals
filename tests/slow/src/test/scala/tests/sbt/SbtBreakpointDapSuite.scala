package tests.sbt

import tests.BaseSbtServerSuite
import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseBreakpointDapSuite

class SbtBreakpointDapSuite
    extends BaseBreakpointDapSuite(
      "sbt-debug-breakpoint",
      SbtServerInitializer,
      SbtBuildLayout,
    )
    with BaseSbtServerSuite
