package scala.meta.internal.metals

trait TelemetryLevel {
  val textValue: String
  val enabled: Boolean
  protected val level: Int
}

object TelemetryLevel {

  implicit val levelOrdering: Ordering[TelemetryLevel] = Ordering.by(_.level)

  case object Off extends TelemetryLevel {
    val textValue: String = "off"
    val level: Int = 0
    val enabled = false
  }

  case object Anonymous extends TelemetryLevel {
    val textValue: String = "anonymous"
    val level: Int = 1
    val enabled = true
  }

  case object Full extends TelemetryLevel {
    val textValue: String = "full"
    val level: Int = 2
    val enabled = true
  }

  val default = Off

  def fromString(textValue: String): TelemetryLevel = {
    textValue match {
      case Anonymous.textValue => Anonymous
      case Full.textValue => Full
      case _ => Off
    }
  }
}

