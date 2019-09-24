package scala.meta.internal.metals

import scala.meta.internal.mtags

object BuildInfo extends mtags.BuildInfo("metals") {
  def scala211 = getString
  def scala212 = getString
  def scala213 = getString
  def sbtBloop = getString
  def scalaVersion = getString
  def bloopVersion = getString
  def sbtBloopVersion = getString
  def scalafmtVersion = getString
  def metalsVersion = getString
  def supportedScalaVersions = getStringList
  def deprecatedScalaVersions = getStringList
  def semanticdbVersion = getString
  def gradleBloopVersion = getString
  def bspVersion = getString
  def localSnapshotVersion = getString
  def scalametaVersion = getString
}
