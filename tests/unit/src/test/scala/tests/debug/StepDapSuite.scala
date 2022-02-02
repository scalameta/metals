package tests.debug

import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class StepDapSuite
    extends BaseStepDapSuite(
      s"debug-step",
      QuickBuildInitializer,
      QuickBuildLayout
    )
