package scala.meta.internal.metals

import scala.meta.internal.mtags

object ScalaVersions {

  /** Non-Lightbend compilers often use a suffix, such as `-bin-typelevel-4` */
  def dropVendorSuffix(version: String): String =
    version.replaceAll("-bin-.*", "")

  private val _isDeprecatedScalaVersion: Set[String] =
    BuildInfo.deprecatedScalaVersions.toSet
  private val _isSupportedScalaVersion: Set[String] =
    BuildInfo.supportedScalaVersions.toSet
  def isSupportedScalaVersion(version: String): Boolean =
    _isSupportedScalaVersion(dropVendorSuffix(version))
  def isDeprecatedScalaVersion(version: String): Boolean =
    _isDeprecatedScalaVersion(dropVendorSuffix(version))
  def isSupportedScalaBinaryVersion(scalaVersion: String): Boolean =
    Set("2.13", "2.12", "2.11").exists { binaryVersion =>
      scalaVersion.startsWith(binaryVersion)
    }

  val isLatestScalaVersion: Set[String] =
    Set(BuildInfo.scala213, BuildInfo.scala212, BuildInfo.scala211)

  def recommendedVersion(scalaVersion: String): String = BuildInfo.scala212

  def isCurrentScalaCompilerVersion(version: String): Boolean =
    ScalaVersions.dropVendorSuffix(version) == mtags.BuildInfo.scalaCompilerVersion
}
