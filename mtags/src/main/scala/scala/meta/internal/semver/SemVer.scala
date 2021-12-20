package scala.meta.internal.semver

import scala.util.Try

object SemVer {

  case class Version(
      major: Int,
      minor: Int,
      patch: Int,
      releaseCandidate: Option[Int],
      milestone: Option[Int]
  ) {
    def >(that: Version): Boolean = {
      lazy val baseVersionEqual =
        this.major == this.major && this.minor == that.minor && this.patch == that.patch

      this.major > that.major ||
      (this.major == that.major && this.minor > that.minor) ||
      (this.major == that.major && this.minor == that.minor && this.patch > that.patch) ||
      // 3.0.0-RC1 > 3.0.0-M1
      baseVersionEqual && this.releaseCandidate.isDefined && that.milestone.isDefined ||
      // 3.0.0 > 3.0.0-M2 and 3.0.0 > 3.0.0-RC1
      baseVersionEqual && (this.milestone.isEmpty && this.releaseCandidate.isEmpty && (that.milestone.isDefined || that.releaseCandidate.isDefined)) ||
      // 3.0.0-RC2 > 3.0.0-RC1
      baseVersionEqual && comparePreRelease(
        that,
        (v: Version) => v.releaseCandidate
      ) ||
      // 3.0.0-M2 > 3.0.0-M1
      baseVersionEqual && comparePreRelease(that, (v: Version) => v.milestone)

    }

    def >=(that: Version): Boolean = this > that || this == that

    private def comparePreRelease(
        that: Version,
        preRelease: Version => Option[Int]
    ): Boolean = {
      val thisPrerelease = preRelease(this)
      val thatPrerelease = preRelease(that)
      this.major == that.major && this.minor == that.minor && this.patch == that.patch &&
      thisPrerelease.isDefined && thatPrerelease.isDefined && thisPrerelease
        .zip(thatPrerelease)
        .exists { case (a, b) => a > b }
    }

    def isStable: Boolean = releaseCandidate.isEmpty && milestone.isEmpty

    override def toString: String =
      List(
        Some(s"$major.$minor.$patch"),
        releaseCandidate.map(s => s"-RC$s"),
        milestone.map(s => s"-M$s")
      ).flatten.mkString("")

  }

  object Version {
    def fromString(version: String): Version = {
      val Array(major, minor, patch) =
        version.replaceAll("(-|\\+).+$", "").split('.').map(_.toInt)

      val prereleaseString = version.stripPrefix(s"$major.$minor.$patch")

      def fromSuffix(name: String) = {
        if (prereleaseString.startsWith(name))
          Try(
            prereleaseString.stripPrefix(name).replaceAll("\\-.*", "").toInt
          ).toOption
        else None
      }
      val releaseCandidate = fromSuffix("-RC")
      val milestone = fromSuffix("-M")

      Version(major, minor, patch, releaseCandidate, milestone)
    }
  }

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {
    Version.fromString(version) >= Version.fromString(minimumVersion)
  }

  def isLaterVersion(earlierVersion: String, laterVersion: String): Boolean = {
    Version.fromString(laterVersion) > Version.fromString(earlierVersion)
  }
}
