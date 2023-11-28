package scala.meta.internal.metals.utils

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.FiniteDuration

case class Timeout(id: String, name: String, minTimeout: FiniteDuration)
object Timeout {
  def apply(name: String, minTimeout: FiniteDuration): Timeout =
    Timeout(name, name, minTimeout)
  def default(name: String, minTimeout: FiniteDuration): Timeout =
    Timeout("default", name, minTimeout)
}

class Timeouts() {
  private val timeouts: AtomicReference[Map[String, AvgTime]] =
    new AtomicReference(Map())

  def measured(timeout: Timeout, time: FiniteDuration): Any = {
    val addToOption: Option[AvgTime] => Option[AvgTime] = {
      case Some(avgTime) => Some(avgTime.add(time))
      case None => Some(AvgTime.of(time))
    }
    timeouts.getAndUpdate(_.updatedWith(timeout.id)(addToOption))
  }

  def getTimeout(timeout: Timeout): FiniteDuration = {
    val Timeout(id, _, minTimeout) = timeout
    timeouts.get
      .get(id)
      .map(_.avgWithMin(minTimeout))
      .getOrElse(minTimeout)
  }

}

case class AvgTime(samples: Int, totalTime: Long) {
  def add(time: FiniteDuration): AvgTime =
    AvgTime(samples + 1, totalTime + time.toMillis)
  def avgWithMin(min: FiniteDuration): FiniteDuration = {
    def max(l1: FiniteDuration, l2: FiniteDuration) = if (l1 > l2) l1 else l2
    val avg = (totalTime / samples)
    val avg3 = FiniteDuration(avg * 3, TimeUnit.MILLISECONDS)
    max(avg3, min)
  }
}

object AvgTime {
  def of(time: FiniteDuration): AvgTime = AvgTime(1, time.toMillis)
}
