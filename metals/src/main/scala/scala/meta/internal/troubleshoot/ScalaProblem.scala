package scala.meta.internal.troubleshoot

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ScalaVersions

/**
 * Class describing different issues that a build target can have that might influence
 * features available to a user. For example without the semanticdb option
 * "find references" feature will not work. Those problems will be reported to a user
 * with an explanation on how to fix it.
 */
sealed abstract class ScalaProblem {

  /**
   * Comprehensive message to be presented to the user.
   */
  def message: String
  protected val hint = "run 'Import build' to enable code navigation."
}

case class UnsupportedScalaVersion(version: String) extends ScalaProblem {
  override def message: String = {
    if (ScalaVersions.isSupportedScalaBinaryVersion(version)) {
      val recommended = ScalaVersions.recommendedVersion(version)
      s"Upgrade to Scala $recommended and " + hint
    } else {
      val versionToUpgradeTo =
        if (ScalaVersions.isScala3Version(version)) {
          s"Scala ${BuildInfo.scala3}"
        } else {
          s"Scala ${BuildInfo.scala213} or ${BuildInfo.scala212}"
        }
      s"Code navigation is not supported for this compiler version, please change to $versionToUpgradeTo and $hint"
    }
  }
}

case class DeprecatedScalaVersion(version: String) extends ScalaProblem {
  override def message: String =
    s"Scala $version might not be supported in upcoming versions of Metals, " +
      s"please upgrade to Scala ${ScalaVersions.recommendedVersion(version)}."
}

case class FutureScalaVersion(version: String) extends ScalaProblem {
  override def message: String = s"Scala $version is not yet supported, " +
    s"please downgrade to Scala ${ScalaVersions.recommendedVersion(version)}."
}

case class SemanticDBDisabled(
    scalaVersion: String,
    bloopVersion: String,
    unsupportedBloopVersion: Boolean
) extends ScalaProblem {
  override def message: String = {
    if (unsupportedBloopVersion) {
      s"""|The installed Bloop server version is $bloopVersion while Metals requires at least Bloop version ${BuildInfo.bloopVersion},
          |To fix this problem please update your Bloop server.""".stripMargin
    } else if (
      ScalaVersions.isSupportedAtReleaseMomentScalaVersion(scalaVersion)
    ) {
      hint.capitalize
    } else {
      "Semanticdb is required for code navigation to work correctly in your project," +
        " however the semanticdb plugin doesn't seem to be enabled. " +
        "Please enable the semanticdb plugin for this project in order for code navigation to work correctly"
    }
  }
}
case class MissingSourceRoot(sourcerootOption: String) extends ScalaProblem {
  override def message: String =
    s"Add the compiler option $sourcerootOption to ensure code navigation works."
}

case object UnsupportedSbtVersion extends ScalaProblem {
  override def message: String =
    "Code navigation is not supported for this sbt version, please upgrade to at least 1.3.2 and reimport the build"
}
case object DeprecatedSbtVersion extends ScalaProblem {
  override def message: String =
    "Code navigation might not be supported in the future for this sbt version, please upgrade to at least 1.3.2 and reimport the build"
}
case object FutureSbtVersion extends ScalaProblem {
  override def message: String =
    "Code navigation for this sbt version is not yet supported"
}
