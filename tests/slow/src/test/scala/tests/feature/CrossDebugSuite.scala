package tests.feature

import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class CrossDebugSuite
    extends BaseCrossDebugSuite(
      "cross-debug",
      QuickBuildInitializer,
      QuickBuildLayout
    )
