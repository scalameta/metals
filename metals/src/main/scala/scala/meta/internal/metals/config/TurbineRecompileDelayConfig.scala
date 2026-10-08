package scala.meta.internal.metals.config

import scala.concurrent.duration._
import scala.util.control.NonFatal

import scala.meta.internal.metals.Testing

/**
 * Configuration for the turbine recompile delay. This is the amount of time
 * to wait after a file change before recompiling with Turbine. During this
 * window, the Java PC uses the SOURCE_PATH fallback to serve updated sources.
 *
 * @param duration The delay duration. Use Duration.Zero for immediate recompile,
 *                 or a large value (e.g. 1 hour) to effectively disable
 *                 turbine recompilation and always use SOURCE_PATH.
 */
final case class TurbineRecompileDelayConfig(duration: FiniteDuration) {

  /** Check if turbine recompilation is effectively disabled (very long delay) */
  def isEffectivelyDisabled: Boolean = duration >= 1.hour
}

object TurbineRecompileDelayConfig {

  /** Default delay of 1 minute for production use */
  val default: TurbineRecompileDelayConfig = TurbineRecompileDelayConfig(
    1.minute
  )

  /** Short delay for testing - tests should complete quickly */
  val testing: TurbineRecompileDelayConfig = TurbineRecompileDelayConfig(
    100.millis
  )

  /** Effectively disable turbine recompilation - always use SOURCE_PATH */
  val disabled: TurbineRecompileDelayConfig =
    TurbineRecompileDelayConfig(1.hour)

  private def defaultConfig: TurbineRecompileDelayConfig =
    if (Testing.isEnabled) testing else default

  /**
   * Parse a duration string like "1 minute", "100ms", "1m", "500 millis".
   * Falls back to default (or testing in test mode) if parsing fails.
   */
  def fromConfig(value: Option[String]): TurbineRecompileDelayConfig = {
    value match {
      case None => defaultConfig
      case Some(str) =>
        try {
          Duration(str) match {
            case fd: FiniteDuration => TurbineRecompileDelayConfig(fd)
            case _ =>
              scribe.warn(
                s"Invalid turbine recompile delay '$str': must be finite"
              )
              defaultConfig
          }
        } catch {
          case NonFatal(e) =>
            scribe.warn(
              s"Invalid turbine recompile delay '$str': ${e.getMessage}"
            )
            defaultConfig
        }
    }
  }
}
