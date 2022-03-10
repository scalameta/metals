package scala.meta.internal.semver

object SemVer {

  case class Version(
      major: Int,
      minor: Int,
      patch: Int,
      releaseCandidate: Option[Int] = None,
      milestone: Option[Int] = None,
      nightlyDate: Option[Int] = None
  ) {
    def >(that: Version): Boolean = {
      val diff = toList
        .zip(that.toList)
        .collectFirst {
          case (a, b) if a - b != 0 => a - b
        }
        .getOrElse(0)
      diff > 0
    }

    def <(that: Version): Boolean =
      that > this

    private def toList: List[Int] = {
      val rcMilestonePart =
        releaseCandidate
          .map(v => List(1, v))
          .orElse(milestone.map(v => List(0, v)))
          .getOrElse(List(2, 0))

      List(major, minor, patch) ++ rcMilestonePart ++
        List(nightlyDate.getOrElse(Int.MaxValue))
    }

    def >=(that: Version): Boolean = this > that || this == that

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
      val Array(major, minor, patch) = parts.take(3).map(_.toInt)
      val (rc, milestone) = parts
        .lift(3)
        .map { v =>
          if (v.startsWith("RC")) (Some(v.stripPrefix("RC").toInt), None)
          else (None, Some(v.stripPrefix("M").toInt))
        }
        .getOrElse((None, None))
      val date = parts.lift(5).map(_.toInt)
      Version(major, minor, patch, rc, milestone, date)
    }

  }

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {
    Version.fromString(version) >= Version.fromString(minimumVersion)
  }

  def isLaterVersion(earlierVersion: String, laterVersion: String): Boolean = {
    Version.fromString(laterVersion) > Version.fromString(earlierVersion)
  }
}
