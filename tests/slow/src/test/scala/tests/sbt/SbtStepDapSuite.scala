package tests.sbt

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseStepDapSuite

class SbtStepDapSuite
    extends BaseStepDapSuite("sbt-debug-step")
    with SbtServerInitializer
    with SbtBuildLayout
