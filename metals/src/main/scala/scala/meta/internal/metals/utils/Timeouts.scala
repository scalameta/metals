package scala.meta.internal.metals.utils

import java.util.concurrent.atomic.AtomicReference

sealed trait Timeout
object Timeout {
  case object NoTimeout extends Timeout
  case object DefaultFlexTimeout extends Timeout
  case class FlexTimeout(id: String, minTimeout: Long) extends Timeout
}

class Timeouts {
  // 3 min
  private val defaultMinTimeout: Long = 3 * 60 * 1000
  private val defaultFlexTimeout: AtomicReference[Option[AvgTime]] =
    new AtomicReference(None)
  private val timeouts: AtomicReference[Map[String, AvgTime]] =
    new AtomicReference(Map())
  def measured(timeout: Timeout, time: Long): Any = {
    val addToOption: Option[AvgTime] => Option[AvgTime] = {
      case Some(avgTime) => Some(avgTime.add(time))
      case None => Some(AvgTime.of(time))
    }
    timeout match {
      case Timeout.NoTimeout =>
      case Timeout.DefaultFlexTimeout =>
        defaultFlexTimeout.getAndUpdate(addToOption(_))
      case Timeout.FlexTimeout(id, _) =>
        timeouts.getAndUpdate(_.updatedWith(id)(addToOption))
    }
  }

  def getTimeout(timeout: Timeout): Option[Long] = {
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
            .getOrElse(defaultMinTimeout)
        )
      case Timeout.NoTimeout => None
    }
  }
}

case class AvgTime(samples: Int, totalTime: Long) {
  def add(time: Long): AvgTime = AvgTime(samples + 1, totalTime + time)
  def avgWithMin(min: Long): Long = {
    def max(l1: Long, l2: Long) = if (l1 > l2) l1 else l2
    val avg = totalTime / samples
    max(avg, min)
  }
}

object AvgTime {
  def of(time: Long): AvgTime = AvgTime(1, time)
}
