package scala.meta.internal.metals.utils

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.Duration

sealed trait Timeout
object Timeout {
  case object NoTimeout extends Timeout
  case object DefaultFlexTimeout extends Timeout
  case class FlexTimeout(id: String, minTimeout: Duration) extends Timeout
}

class Timeouts(defaultMinTimeout: Duration) {
  private val defaultFlexTimeout: AtomicReference[Option[AvgTime]] =
    new AtomicReference(None)
  private val timeouts: AtomicReference[Map[String, AvgTime]] =
    new AtomicReference(Map())
  def measured(timeout: Timeout, time: Duration): Any = {
    val addToOption: Option[AvgTime] => Option[AvgTime] = {
      case Some(avgTime) => Some(avgTime.add(time))
      case None => Some(AvgTime.of(time))
    }
    timeout match {
      case Timeout.DefaultFlexTimeout =>
        defaultFlexTimeout.getAndUpdate(addToOption(_))
      case Timeout.FlexTimeout(id, _) =>
        timeouts.getAndUpdate(_.updatedWith(id)(addToOption))
      case _ =>
    }
  }

  def getTimeout(timeout: Timeout): Option[Duration] = {
    timeout match {
      case Timeout.DefaultFlexTimeout =>
        Some(
          defaultFlexTimeout.get
            .map(_.avgWithMin(defaultMinTimeout))
            .getOrElse(defaultMinTimeout)
        )
      case Timeout.FlexTimeout(id, minTimeout) =>
        Some(
          timeouts.get
            .get(id)
            .map(_.avgWithMin(minTimeout))
            .getOrElse(minTimeout)
        )
      case Timeout.NoTimeout => None
    }
  }
}

case class AvgTime(samples: Int, totalTime: Long) {
  def add(time: Duration): AvgTime =
    AvgTime(samples + 1, totalTime + time.toMillis)
  def avgWithMin(min: Duration): Duration = {
    def max(l1: Duration, l2: Duration) = if (l1 > l2) l1 else l2
    val avg = (totalTime / samples)
    val avg3 = Duration(avg * 3, TimeUnit.MILLISECONDS)
    max(avg3, min)
  }
}

object AvgTime {
  def of(time: Duration): AvgTime = AvgTime(1, time.toMillis)
}
