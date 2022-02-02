package tests.sbt

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseStepDapSuite

class SbtStepDapSuite
    extends BaseStepDapSuite(
      s"sbt-debug-step",
      SbtServerInitializer,
      SbtBuildLayout
    )
