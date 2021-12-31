package scala.meta.internal.metals

sealed abstract class ScalafmtDialect(val value: String)
object ScalafmtDialect {
  case object Scala3 extends ScalafmtDialect("scala3")
  case object Scala213 extends ScalafmtDialect("scala213")
  case object Scala213Source3 extends ScalafmtDialect("scala213source3")
  case object Scala212 extends ScalafmtDialect("scala212")
  case object Scala212Source3 extends ScalafmtDialect("scala212source3")
  case object Scala211 extends ScalafmtDialect("scala211")

  implicit val ord: Ordering[ScalafmtDialect] = new Ordering[ScalafmtDialect] {

    override def compare(x: ScalafmtDialect, y: ScalafmtDialect): Int =
      prio(x) - prio(y)

    private def prio(d: ScalafmtDialect): Int = d match {
      case Scala211 => 1
      case Scala212 => 2
      case Scala212Source3 => 3
      case Scala213 => 4
      case Scala213Source3 => 5
      case Scala3 => 6
    }
  }

  def fromString(v: String): Option[ScalafmtDialect] = v.toLowerCase match {
    case "default" => Some(Scala213)
    case "scala211" => Some(Scala211)
    case "scala212" => Some(Scala212)
    case "scala212source3" => Some(Scala212Source3)
    case "scala213" => Some(Scala213)
    case "scala213source3" => Some(Scala213Source3)
    case "scala3" => Some(Scala3)
    case _ => None
  }
}
