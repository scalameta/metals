import scala.util.Try

final case class Version(
    major: Int,
    minor: Int,
    patch: Int,
    rc: Option[Int],
    nigthlyDate: Option[Int],
    original: String,
) {

  def >(o: Version): Boolean = {
    val diff = toList
      .zip(o.toList)
      .collectFirst {
        case (a, b) if a - b != 0 => a - b
      }
      .getOrElse(0)
    diff > 0
  }

  def >=(o: Version): Boolean = this == o || this > o

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

object Version {
  def parse(v: String): Option[Version] = {
    Try {
      val parts = v.split("\\.|-RC|-")
      if (parts.size < 3) None
      else {
        val Array(major, minor, patch) = parts.take(3).map(_.toInt)
        val rc = parts.lift(3).map(_.toInt)
        // format is "$major.$minor.$patch-RC$rc-bin-$date-hash-NIGHTLY"
        // or when locally published "$major.$minor.$patch-RC$rc-bin-SNAPSHOT"
        if (parts.lift(5) == Some("SNAPSHOT")) {
          Some(Version(major, minor, patch, rc, None, v))
        } else {
          val date = parts.lift(5).map(_.toInt)
          Some(Version(major, minor, patch, rc, date, v))
        }
      }

    }.toOption.flatten
  }

  implicit val ordering: Ordering[Version] =
    new Ordering[Version] {
      override def compare(x: Version, y: Version): Int =
        if (x == y) 0
        else if (x > y) 1
        else -1
    }
}
