package scala.meta.internal.metals
import java.util.concurrent.TimeUnit

import com.google.common.base.Stopwatch
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SimpleTimer {
  val logger: Logger = LoggerFactory.getLogger("mtags")

  def timedThunk[T](
      didWhat: String,
      onlyIf: Boolean = true,
      thresholdMillis: Long = 0
  )(thunk: => T): T = {
    val timer = Stopwatch.createStarted()
    val result = thunk
    timer.stop()
    if (
      onlyIf && (thresholdMillis == 0 ||
        timer.elapsed(TimeUnit.MILLISECONDS) > thresholdMillis)
    ) {
      logger.debug(s"time: $didWhat in $timer")
    }

    result
  }
}
