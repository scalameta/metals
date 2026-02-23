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

  private def logDuration(message: => String): Unit = {
    if (MetalsServerConfig.isTesting) scribe.debug(message)
    else scribe.info(message)
  }

  // Records a metric for how long a future takes to complete.
  def recorded[T](
      metricName: String,
      thunk: => Future[T],
  ): Future[T] = {
    val timer = new Timer(time)
    val result = thunk
    result.onComplete { _ =>
      metrics.recordEvent(infra.Event.duration(metricName, timer.elapsed))
    }
    result
  }

  def timed[T](
      didWhat: String,
      reportStatus: Boolean = false,
      onlyIf: Boolean = false,
      metricName: Option[String] = None,
  )(thunk: => Future[T]): Future[T] = {
    withTimer(didWhat, reportStatus, onlyIf)(thunk).map { case (timer, value) =>
      metricName.foreach { name =>
        metrics.recordEvent(infra.Event.duration(name, timer.elapsed))
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
      logDuration(s"time: $didWhat in $timer")
    }

    metricName.foreach { name =>
      metrics.recordEvent(infra.Event.duration(name, timer.elapsed))
    }

    result
  }

  def withTimer[T](
      didWhat: String,
      reportStatus: Boolean,
      onlyIf: Boolean,
  )(
      thunk: => Future[T]
  ): Future[(Timer, T)] = {
    val elapsed = new Timer(time)
    val result = thunk
    result.map { value =>
      if (onlyIf && (reportStatus || elapsed.isLogWorthy)) {
        logDuration(s"time: $didWhat in $elapsed")
      }
      (elapsed, value)
    }
  }
}
