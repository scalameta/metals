package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
 * Helper class to provider functionality around timers.
 */
final class TimerProvider(time: Time)(implicit ec: ExecutionContext) {
  def timed[T](
      didWhat: String,
      reportStatus: Boolean = false
  )(thunk: => Future[T]): Future[T] = {
    withTimer(didWhat, reportStatus)(thunk).map { case (_, value) =>
      value
    }
  }

  def timedThunk[T](
      didWhat: String,
      onlyIf: Boolean = true,
      thresholdMillis: Long = 0
  )(thunk: => T): T = {
    val elapsed = new Timer(time)
    val result = thunk
    if (
      onlyIf && (thresholdMillis == 0 || elapsed.elapsedMillis > thresholdMillis)
    ) {
      scribe.info(s"time: $didWhat in $elapsed")
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
