package scala.meta.internal.semver

import java.util.logging.Logger

import scala.util.Try

object SemVer {

  private val logger: Logger = Logger.getLogger(getClass.getName)

  case class Version(
      major: Int,
      minor: Int,
      patch: Int,
      releaseCandidate: Option[Int] = None,
      milestone: Option[Int] = None,
      nightlyDate: Option[Int] = None,
      developmentBuild: Option[Long] = None,
      isStable: Boolean = true
  ) extends Ordered[Version] {
    private def toList: List[Int] = {
      val rcMilestonePart =
        releaseCandidate
          .map(v => List(1, v))
          .orElse(milestone.map(v => List(0, v)))
          .getOrElse(List(2, 0))

      List(major, minor, patch) ++ rcMilestonePart ++
        List(nightlyDate.getOrElse(Int.MaxValue))
    }

    def compare(that: Version): Int = {
      val diff: Int = toList
        .zip(that.toList)
        .collectFirst {
          case (a, b) if a - b != 0 => a - b
        }
        .getOrElse(0)
      if (diff == 0) 0
      else if (diff > 0) 1
      else -1
    }

    override def toString: String =
      List(
        Some(s"$major.$minor.$patch"),
        releaseCandidate.map(s => s"-RC$s"),
        milestone.map(s => s"-M$s"),
        nightlyDate.map(d => s"-$d-NIGHTLY")
      ).flatten.mkString("")

  }

  object Version {

    /** Suggestion order: stable before pre-release, then newest first. */
    val stableFirst: Ordering[Version] = new Ordering[Version] {
      def compare(left: Version, right: Version): Int =
        if (left.isStable != right.isStable) {
          if (left.isStable) -1 else 1
        } else {
          val comparison = right.compare(left)
          if (comparison != 0) comparison
          else
            (left.developmentBuild, right.developmentBuild) match {
              case (Some(leftBuild), Some(rightBuild)) =>
                java.lang.Long.compare(rightBuild, leftBuild)
              case _ => 0
            }
        }
    }

    def fromString(version: String): Version = {
      val numericCore = version.takeWhile(char => char.isDigit || char == '.')
      val numbers = numericCore.split('.').map(part => Try(part.toInt).toOption)
      val (major, minor, patch) =
        numbers match {
          case Array(Some(major), Some(minor), Some(patch), _*) =>
            (major, minor, patch)
          case Array(Some(major), Some(minor), _*) =>
            (major, minor, 0)
          case Array(Some(major), _*) =>
            (major, 0, 0)
          case _ =>
            logger.warning(s"Version $version is invalid.")
            throw new IllegalArgumentException(s"Version $version is invalid")
        }
      val qualifiers = qualifiersOf(version, numericCore)
      // specific condition for Scala 3 nightlies - 3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY
      val date =
        if (qualifiers.contains("NIGHTLY")) qualifiers.collectFirst {
          case token if token.length == 8 && token.forall(_.isDigit) =>
            token.toInt
        }
        else None
      val developmentBuild = qualifiers.headOption
        .filter(_.forall(_.isDigit))
        .flatMap(token => Try(token.toLong).toOption)
      Version(
        major,
        minor,
        patch,
        numberAfter("RC", qualifiers),
        milestoneNumber(qualifiers),
        date,
        developmentBuild = developmentBuild,
        isStable = isStable(qualifiers)
      )
    }

    /** Everything past the leading digits and dots, as in `RC1`, `bin`, `NIGHTLY`. */
    private def qualifiersOf(
        version: String,
        numericCore: String
    ): List[String] =
      version
        .drop(numericCore.length)
        .split("[-._+]")
        .filter(_.nonEmpty)
        .toList

    private def numberAfter(
        prefix: String,
        qualifiers: List[String]
    ): Option[Int] =
      qualifiers.collectFirst {
        case token
            if token.startsWith(prefix) && token.length > prefix.length &&
              token.drop(prefix.length).forall(_.isDigit) =>
          token.drop(prefix.length).toInt
      }

    private def milestoneNumber(qualifiers: List[String]): Option[Int] =
      numberAfter("M", qualifiers).orElse(
        qualifiers.collectFirst {
          case token if token.equalsIgnoreCase("MF") =>
            0
        }
      )

    private val preReleaseNames = Set(
      "alpha", "beta", "cr", "dev", "m", "mf", "milestone", "nightly", "pre",
      "preview", "rc", "snap", "snapshot"
    )

    private def isStable(qualifiers: List[String]): Boolean =
      !qualifiers.zipWithIndex.exists { case (token, index) =>
        isPreReleaseName(token) ||
        isDevelopmentBuild(token, isFirstToken = index == 0)
      }

    private def isPreReleaseName(token: String): Boolean = {
      val letters = token.toLowerCase.takeWhile(_.isLetter)
      preReleaseNames.contains(letters) &&
      token.drop(letters.length).forall(_.isDigit)
    }

    /** Commit distance or hash, as in `3.7-4972921` and `3.2-148-d9af944`. */
    private def isDevelopmentBuild(
        token: String,
        isFirstToken: Boolean
    ): Boolean =
      (isFirstToken && token.forall(_.isDigit)) ||
        (token.length >= 6 && token.forall(isHexDigit))

    private def isHexDigit(char: Char): Boolean = {
      val lowerCase = char.toLower
      char.isDigit || (lowerCase >= 'a' && lowerCase <= 'f')
    }
  }

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {
    Version.fromString(version) >= Version.fromString(minimumVersion)
  }

  def isLaterVersion(earlierVersion: String, laterVersion: String): Boolean = {
    Version.fromString(laterVersion) > Version.fromString(earlierVersion)
  }
}
