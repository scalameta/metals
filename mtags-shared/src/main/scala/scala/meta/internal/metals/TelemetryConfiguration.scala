package scala.meta.internal.metals

/**
 * Telemetry configuration class.
 *
 * @param telemetryLevel what kinds of data should we send to the telemetry server
 * @param includeCodeSnippet should we also include code snippets that caused the crash
 */
case class TelemetryConfiguration(
    telemetryLevel: TelemetryLevel,
    includeCodeSnippet: Boolean
)

object TelemetryConfiguration {
  def default: TelemetryConfiguration =
    TelemetryConfiguration(TelemetryLevel.default, false)
}

/**
 * Telemetry level set by the user or downgraded to the editor telemetry level.
 * It controls the kind of reports being sent and its verbosity level.
 */
trait TelemetryLevel {
  val textValue: String
  val enabled: Boolean
  protected val level: Int

  def sendCrashes: Boolean = level >= 1
  def sendErrors: Boolean = level >= 2
  def sendUsage: Boolean = level >= 3
}

object TelemetryLevel {

  implicit val levelOrdering: Ordering[TelemetryLevel] = Ordering.by(_.level)

  case object Off extends TelemetryLevel {
    val textValue: String = "off"
    val level: Int = 0
    val enabled = false
  }

  case object Crash extends TelemetryLevel {
    val textValue: String = "crash"
    val level: Int = 1
    val enabled = true
  }

  case object Error extends TelemetryLevel {
    val textValue: String = "error"
    val level: Int = 2
    val enabled = true
  }

  case object All extends TelemetryLevel {
    val textValue: String = "all"
    val level: Int = 3
    val enabled = true
  }

  val default = Off

  def fromString(textValue: String): TelemetryLevel = {
    textValue match {
      case Crash.textValue => Crash
      case Error.textValue => Error
      case All.textValue => All
      case _ => Off
    }
  }
}
