package scala.meta.internal.metals

object ScalaVersions {
  val isSupportedScalaVersion: Set[String] =
    BuildInfo.supportedScalaVersions.toSet
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
