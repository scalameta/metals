package scala.meta.internal.metals

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
    Set("2.12", "2.11").exists { binaryVersion =>
      scalaVersion.startsWith(binaryVersion)
    }

  val isLatestScalaVersion: Set[String] =
    Set(BuildInfo.scala212, BuildInfo.scala211)

  def recommendedVersion(scalaVersion: String): String = {
    if (scalaVersion.startsWith("2.11")) BuildInfo.scala211
    else BuildInfo.scala212
  }
}
