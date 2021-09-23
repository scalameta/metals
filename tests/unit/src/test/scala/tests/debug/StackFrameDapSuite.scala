package tests.debug

import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class StackFrameDapSuite
    extends BaseStackFrameDapSuite(
      "debug-stack-frame",
      QuickBuildInitializer,
      QuickBuildLayout
    )
