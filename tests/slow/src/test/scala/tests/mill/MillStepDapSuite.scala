package tests.mill

import scala.meta.internal.metals.BuildInfo

import tests.MillBuildLayout
import tests.MillServerInitializer
import tests.debug.BaseStepDapSuite

class MillStepDapSuite
    extends BaseStepDapSuite(
      "mill-debug-step",
      MillServerInitializer,
      MillBuildLayout,
    ) {

  // otherwise we get both Scala 2.12 and 2.13 dependencies, which is more tricky for the tests
  override def scalaVersion: String = BuildInfo.scala212

}
