package scala.meta.internal.semver

import scala.util.Try

object SemVer {

  case class Version(
      major: Int,
      minor: Int,
      patch: Int,
      releaseCandidate: Option[Int] = None,
      milestone: Option[Int] = None,
      nightlyDate: Option[Int] = None
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
    def fromString(version: String): Version = {
      val parts = version.split("\\.|-")
      val Array(major, minor, patch) =
        parts.take(3).map(tryToInt)
      val (rc, milestone) = parts
        .lift(3)
        .map { v =>
          if (v.startsWith("RC")) (Some(tryToInt(v.stripPrefix("RC"))), None)
          else if (v.startsWith("M")) (None, Some(tryToInt(v.stripPrefix("M"))))
          else (None, None)
        }
        .getOrElse((None, None))
      // specific condition for Scala 3 nightlies - 3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY
      val date =
        if (parts.lift(7).contains("NIGHTLY"))
          parts.lift(5).flatMap(d => Try(d.toInt).toOption)
        else None
      Version(major, minor, patch, rc, milestone, date)
    }

  }

  private def tryToInt(s: String): Int = Try { s.toInt }.toOption.getOrElse(0)

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {
    Version.fromString(version) >= Version.fromString(minimumVersion)
  }

  def isLaterVersion(earlierVersion: String, laterVersion: String): Boolean = {
    Version.fromString(laterVersion) > Version.fromString(earlierVersion)
  }
}
