package tests.sbt

import tests.BaseSbtServerSuite
import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseEvaluationDapSuite

class SbtEvaluationDapSuite
    extends BaseEvaluationDapSuite(
      "sbt-debug-evaluation",
      SbtServerInitializer,
      SbtBuildLayout,
    )
    with BaseSbtServerSuite
