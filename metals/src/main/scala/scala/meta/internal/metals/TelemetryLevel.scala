package scala.meta.internal.metals

sealed class TelemetryLevel(private[TelemetryLevel] val level: Int, val stringValue: String) {
  def enabled: Boolean = level > TelemetryLevel.Off.level
  def reportCrashes: Boolean = level >= TelemetryLevel.Crash.level
  def reportErrors: Boolean = level >= TelemetryLevel.Error.level
  def reportAll: Boolean = level >= TelemetryLevel.Crash.level
}

object TelemetryLevel {
  case object Off extends TelemetryLevel(0, "off")
  case object Crash extends TelemetryLevel(1, "crash")
  case object Error extends TelemetryLevel(2, "error")
  case object All extends TelemetryLevel(Int.MaxValue, "all")

  def default = All

  def fromString(value: String): Option[TelemetryLevel] =
    Option(value).map(_.trim().toLowerCase()).collect {
      case Off.stringValue => Off
      case Crash.stringValue => Crash
      case Error.stringValue => Error
      case All.stringValue => All
    }
}
