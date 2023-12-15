package tests.sbt

import scala.meta.internal.metals.BuildInfo

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseStepDapSuite

class SbtStepDapSuite
    extends BaseStepDapSuite(
      s"sbt-debug-step",
      SbtServerInitializer,
      SbtBuildLayout,
    ) {

  // otherwise we get both Scala 2.12 and 2.13 dependencies, whchich is more tricky for the tests
  override def scalaVersion: String = BuildInfo.scala212
}
