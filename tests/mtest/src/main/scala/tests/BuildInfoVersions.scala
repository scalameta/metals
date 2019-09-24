package tests

import scala.meta.internal.mtags.BuildInfo

object BuildInfoVersions extends BuildInfo("mtest") {
  def scala212 = getString("scala212")
  def scalaVersion = getString("scalaVersion")
}
