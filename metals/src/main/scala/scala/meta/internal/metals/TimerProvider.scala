package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.infra
import scala.meta.internal.infra.NoopMonitoringClient

object TimerProvider {
  def empty: TimerProvider =
    new TimerProvider(time = Time.system, metrics = new NoopMonitoringClient())(
      ExecutionContext.global
    )
}

/**
 * Helper class to provider functionality around timers.
 */
final class TimerProvider(time: Time, metrics: infra.MonitoringClient)(implicit
    ec: ExecutionContext
) {
  def timed[T](
      didWhat: String,
      reportStatus: Boolean = false,
      onlyIf: Boolean = false,
      thresholdMillis: Long = 2,
      metricName: Option[String] = None,
  )(thunk: => Future[T]): Future[T] = {
    withTimer(didWhat, reportStatus)(thunk).map { case (timer, value) =>
      metricName.foreach { name =>
        metrics.recordEvent(infra.Event.duration(name, timer.elapsed))
      }
      if (onlyIf && timer.elapsedMillis < thresholdMillis) {
        scribe.info(s"time: $didWhat in $timer")
      }
      value
    }
  }

  def timedThunk[T](
      didWhat: String,
      onlyIf: Boolean = true,
      thresholdMillis: Long = 0,
      metricName: Option[String] = None,
  )(thunk: => T): T = {
    val timer = new Timer(time)
    val result = thunk
    if (
      onlyIf && (thresholdMillis == 0 || timer.elapsedMillis > thresholdMillis)
    ) {
      scribe.info(s"time: $didWhat in $timer")
    }

    metricName.foreach { name =>
      metrics.recordEvent(infra.Event.duration(name, timer.elapsed))
    }

    result
  }

  def withTimer[T](didWhat: String, reportStatus: Boolean)(
      thunk: => Future[T]
  ): Future[(Timer, T)] = {
    val elapsed = new Timer(time)
    val result = thunk
    result.map { value =>
      if (reportStatus || elapsed.isLogWorthy) {
        scribe.info(s"time: $didWhat in $elapsed")
      }
      (elapsed, value)
    }
  }
}
