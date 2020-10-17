package scala.meta.internal.semver

object SemVer {

  case class Version(major: Int, minor: Int, patch: Int) {
    def >(that: Version): Boolean = {
      this.major > that.major ||
      (this.major == that.major && this.minor > that.minor) ||
      (this.major == that.major && this.minor == that.minor && this.patch > that.patch)
    }

    def >=(that: Version): Boolean = this > that || this == that

    override def toString(): String = s"${major}.${minor}.${patch}"
  }

  object Version {
    def fromString(version: String): Version = {
      val Array(major, minor, patch) =
        version.replaceAll("(-|\\+).+$", "").split('.').map(_.toInt)

      Version(major, minor, patch)
    }
  }

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {
    Version.fromString(version) >= Version.fromString(minimumVersion)
  }
}
