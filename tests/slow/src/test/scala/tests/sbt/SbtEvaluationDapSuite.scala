package tests.sbt

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseEvaluationDapSuite

class SbtEvaluationDapSuite
    extends BaseEvaluationDapSuite(
      "sbt-debug-evaluation",
      SbtServerInitializer,
      SbtBuildLayout
    )
