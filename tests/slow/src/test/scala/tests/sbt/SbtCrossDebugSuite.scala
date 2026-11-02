package tests.sbt

import tests.BaseSbtServerSuite
import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.feature.BaseCrossDebugSuite

class SbtCrossDebugSuite
    extends BaseCrossDebugSuite(
      "sbt-cross-debug",
      SbtServerInitializer,
      SbtBuildLayout,
    )
    with BaseSbtServerSuite
