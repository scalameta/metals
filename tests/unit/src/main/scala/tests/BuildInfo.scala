package tests

import scala.meta.internal.mtags

object BuildInfo extends mtags.BuildInfo("unit") {
  def testResourceDirectory = getAbsolutePath
  def sourceroot = getAbsolutePath
  def targetDirectory = getAbsolutePath
  def scalaVersion = getAbsolutePath
}
