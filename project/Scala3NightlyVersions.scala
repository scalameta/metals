import scala.jdk.CollectionConverters._
import scala.util.Try

object Scala3NightlyVersions {

  val broken =
    Set(
      "3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY",
      "3.2.0-RC1-bin-20220308-29073f1-NIGHTLY",
      "3.1.3-RC1-bin-20220406-73cda0c-NIGHTLY",
    ).flatMap(DottyVersion.parse)

  /**
   * Fetches last 5 nightly releases.
   * They should come at least after the last supported scala3 version
   * otherwise there is no point to use these versions.
   */
  def nightlyReleasesAfter(version: String): List[DottyVersion] = {
    val lastVersion = DottyVersion.parse(version) match {
      case Some(v) => v
      case None =>
        throw new Exception(s"Can't parse dotty versions from $version")
    }

    try {
      fetchScala3NightlyVersions()
        .filter(_ > lastVersion)
        .sorted
        .takeRight(5)
    } catch {
      case e: Throwable =>
        println("Fetching Scala3 nigthly versions failed")
        e.printStackTrace()
        Nil
    }
  }

  def nonPublishedNightlyVersions(): List[DottyVersion] = {
    val all = fetchScala3NightlyVersions()
    lastPublishedMtagsForNightly() match {
      case None =>
        println("Error: Unable to find last nightly mtag")
        Nil
      case Some(last) =>
        all.filter(_ > last)
    }
  }

  private def fetchScala3NightlyVersions(): List[DottyVersion] = {
    coursierapi.Complete
      .create()
      .withInput("org.scala-lang:scala3-compiler_3:")
      .complete()
      .getCompletions()
      .asScala
      .filter(_.endsWith("NIGHTLY"))
      .flatMap(DottyVersion.parse)
      .filter(!broken.contains(_))
      .toList
  }

  private def lastPublishedMtagsForNightly(): Option[DottyVersion] = {
    coursierapi.Complete
      .create()
      .withInput("org.scalameta:mtags_3")
      .complete()
      .getCompletions()
      .asScala
      .filter(_.endsWith("NIGHTLY"))
      .map(_.stripPrefix("mtags_"))
      .flatMap(DottyVersion.parse)
      .sorted
      .lastOption
  }

  case class DottyVersion(
      major: Int,
      minor: Int,
      patch: Int,
      rc: Option[Int],
      nigthlyDate: Option[Int],
      original: String,
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

    override def toString(): String = original

    private def toList: List[Int] =
      List(
        major,
        minor,
        patch,
        rc.getOrElse(Int.MaxValue),
        nigthlyDate.getOrElse(Int.MaxValue),
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

    implicit val ordering: Ordering[DottyVersion] =
      new Ordering[DottyVersion] {
        override def compare(x: DottyVersion, y: DottyVersion): Int =
          if (x == y) 0
          else if (x > y) 1
          else -1
      }
  }
}
