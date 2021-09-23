package tests.sbt

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseStackFrameDapSuite

class SbtStackFrameDapSuite
    extends BaseStackFrameDapSuite(
      "sbt-debug-stack-frame",
      SbtServerInitializer,
      SbtBuildLayout
    )
