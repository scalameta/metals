import scala.jdk.CollectionConverters._
import scala.util.Try

object Scala3NightlyVersions {

  /**
   * Fetches last 5 nightly releases.
   * They should come at least after the last supported scala3 version
   * otherwise there is no point to use these versions.
   */
  def nightlyReleasesAfter(version: String): List[String] = {

    val lastVersion = DottyVersion.parse(version) match {
      case Some(v) => v
      case None =>
        throw new Exception(s"Can't parse dotty versions from $version")
    }

    try {
      coursierapi.Complete
        .create()
        .withInput("org.scala-lang:scala3-compiler_3:")
        .complete()
        .getCompletions()
        .asScala
        .filter(_.endsWith("NIGHTLY"))
        .flatMap(DottyVersion.parse)
        .collect {
          case v if v > lastVersion => v
        }
        .toList
        .sortWith(_ > _)
        .map(_.original)
        .takeRight(5)
    } catch {
      case e: Throwable =>
        println("Fetching Scala3 nigthly versions failed")
        e.printStackTrace()
        Nil
    }
  }

  case class DottyVersion(
      major: Int,
      minor: Int,
      patch: Int,
      rc: Option[Int],
      nigthlyDate: Option[Int],
      original: String
  ) {

    def >(o: DottyVersion): Boolean = {
      val diff = toList
        .zip(o.toList)
        .collectFirst {
          case (a, b) if a - b != 0 => a - b
        }
        .getOrElse(0)
      diff > 0
    }

    private def toList: List[Int] =
      List(
        major,
        minor,
        patch,
        rc.getOrElse(Int.MaxValue),
        nigthlyDate.getOrElse(Int.MaxValue)
      )
  }

  object DottyVersion {
    def parse(v: String): Option[DottyVersion] = {
      Try {
        val parts = v.split("\\.|-RC|-")
        if (parts.size < 3) None
        else {
          val Array(major, minor, patch) = parts.take(3).map(_.toInt)
          val rc = parts.lift(3).map(_.toInt)
          // format is "$major.$minor.$patch-RC$rc-bin-$date-hash-NIGHTLY"
          val date = parts.lift(5).map(_.toInt)
          Some(DottyVersion(major, minor, patch, rc, date, v))
        }

      }.toOption.flatten
    }
  }
}
