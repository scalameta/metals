package scala.meta.internal.metals

sealed abstract class ScalafmtDialect(val value: String)
object ScalafmtDialect {
  case object Scala213 extends ScalafmtDialect("scala213")
  case object Scala213Source3 extends ScalafmtDialect("scala213source3")
  case object Scala3 extends ScalafmtDialect("scala3")

  implicit val ord: Ordering[ScalafmtDialect] = new Ordering[ScalafmtDialect] {

    override def compare(x: ScalafmtDialect, y: ScalafmtDialect): Int =
      prio(x) - prio(y)

    private def prio(d: ScalafmtDialect): Int = d match {
      case Scala213 => 1
      case Scala213Source3 => 2
      case Scala3 => 3
    }
  }

  def fromString(v: String): Option[ScalafmtDialect] = v.toLowerCase match {
    case "default" => Some(Scala213)
    case "scala213" => Some(Scala213)
    case "scala213source3" => Some(Scala213Source3)
    case "scala3" => Some(Scala3)
    case _ => None
  }
}
