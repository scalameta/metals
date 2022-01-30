package tests.sbt

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseStepDapSuite

abstract class SbtStepDapSuite(
    useVirtualDocuments: Boolean,
    suiteNameSuffix: String
) extends BaseStepDapSuite(
      useVirtualDocuments,
      s"sbt-debug-step-$suiteNameSuffix",
      SbtServerInitializer,
      SbtBuildLayout
    )

class SbtStepDapSuiteSaveToDiskSuite
    extends SbtStepDapSuite(false, "save-to-disk")

class SbtStepDapSuiteVirtualDocSuite
    extends SbtStepDapSuite(true, "virtual-docs")
