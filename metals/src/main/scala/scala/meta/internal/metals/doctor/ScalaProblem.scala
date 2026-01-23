package scala.meta.internal.metals.doctor

import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ScalaVersions
import scala.meta.io.AbsolutePath

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
  private def recommendedVersion = ScalaVersions.recommendedVersion(version)
  override def message: String =
    s"Scala $version might no longer be supported by Metals in the future, " +
      s"to get the best support possible it's recommended to update to at least $recommendedVersion."
}

case class DeprecatedRemovedScalaVersion(version: String) extends ScalaProblem {
  private def recommendedVersion = ScalaVersions.recommendedVersion(version)
  override def message: String =
    s"Support for Scala $version is no longer being updated or fixed, " +
      s"it's recommended to update to at least $recommendedVersion."
}

case class FutureScalaVersion(version: String) extends ScalaProblem {
  override def message: String = s"Scala $version is not yet supported, " +
    s"please downgrade to Scala ${ScalaVersions.recommendedVersion(version)}."
}

case class SemanticDBDisabled(
    scalaVersion: String,
    bloopVersion: String,
    unsupportedBloopVersion: Boolean,
) extends ScalaProblem {
  override def message: String = {
    if (unsupportedBloopVersion) {
      s"""|The installed Bloop server version is $bloopVersion while Metals requires at least Bloop version ${BloopServers.minimumBloopVersion},
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
    "Code navigation is not supported for this sbt version, please " +
      s"upgrade to at least ${BuildInfo.minimumSupportedSbtVersion} and reimport the build"
}
case class DeprecatedSbtVersion(sbtVersion: String, scalaVersion: String)
    extends ScalaProblem {
  override def message: String =
    s"sbt version $sbtVersion uses Scala $scalaVersion, which might no longer be supported by Metals in the future. "
  s"To get the best support possible it's recommended to update to at least ${BuildInfo.minimumSupportedSbtVersion} and reimport the build."
}
case class DeprecatedRemovedSbtVersion(sbtVersion: String, scalaVersion: String)
    extends ScalaProblem {
  override def message: String =
    s"sbt version $sbtVersion uses Scala $scalaVersion, which is no longer supported by Metals. " +
      s"To get the best support possible it's recommended to update at least ${BuildInfo.minimumSupportedSbtVersion} and reimport the build."
}
case object FutureSbtVersion extends ScalaProblem {
  override def message: String =
    "Code navigation for this sbt version is not yet supported"
}

case object OutdatedJunitInterfaceVersion extends ScalaProblem {
  override def message: String =
    "Test Explorer will not work properly with this version of junit-interface, please update it to at least com.github.sbt:junit-interface:0.13.3"
}

case object OutdatedMunitInterfaceVersion extends ScalaProblem {
  override def message: String =
    "Running single tests in Test Explorer will not work properly with this version of munit, please update it to at least org.scalameta.munit.1.0.0-M3"
}

case class MissingJdkSources(candidates: List[AbsolutePath])
    extends ScalaProblem {
  private val candidateString = candidates.mkString(", ")
  override def message: String =
    s"Goto definition for Java classes will not work, please install jdk sources in java home. Searched: $candidateString"
}

case class WrongScalaReleaseVersion(current: String, needed: String)
    extends ScalaProblem {
  override def message: String =
    s"Metals is currently running on Java $current, but the build definition requires Java $needed due to the `-release` flag. " +
      s"Please change the Java version used by Metals to at least $needed."
}
