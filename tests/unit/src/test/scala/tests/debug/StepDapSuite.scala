package tests.debug

import tests.QuickBuildInitializer
import tests.QuickBuildLayout

abstract class StepDapSuite(
    useVirtualDocuments: Boolean,
    suiteNameSuffix: String
) extends BaseStepDapSuite(
      useVirtualDocuments,
      s"debug-step-$suiteNameSuffix",
      QuickBuildInitializer,
      QuickBuildLayout
    ) {}

class StepDapSaveToDiskSuite extends StepDapSuite(false, "save-to-disk")

class StepDapVirtualDocSuite extends StepDapSuite(true, "virtual-docs")
