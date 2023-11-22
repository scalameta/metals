package scala.meta.internal.metals.utils

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.FiniteDuration

sealed trait Timeout
object Timeout {
  case object NoTimeout extends Timeout
  case class DefaultFlexTimeout(id: String) extends Timeout
  case class FlexTimeout(id: String, minTimeout: FiniteDuration) extends Timeout
}

class Timeouts(defaultMinTimeout: FiniteDuration) {
  private val defaultFlexTimeout: AtomicReference[Option[AvgTime]] =
    new AtomicReference(None)
  private val timeouts: AtomicReference[Map[String, AvgTime]] =
    new AtomicReference(Map())
  def measured(timeout: Timeout, time: FiniteDuration): Any = {
    val addToOption: Option[AvgTime] => Option[AvgTime] = {
      case Some(avgTime) => Some(avgTime.add(time))
      case None => Some(AvgTime.of(time))
    }
    timeout match {
      case Timeout.DefaultFlexTimeout(_) =>
        defaultFlexTimeout.getAndUpdate(addToOption(_))
      case Timeout.FlexTimeout(id, _) =>
        timeouts.getAndUpdate(_.updatedWith(id)(addToOption))
      case _ =>
    }
  }

  def getNameAndTimeout(timeout: Timeout): Option[(String, FiniteDuration)] = {
    timeout match {
      case Timeout.DefaultFlexTimeout(id) =>
        Some(
          defaultFlexTimeout.get
            .map(_.avgWithMin(defaultMinTimeout))
            .getOrElse(defaultMinTimeout)
        ).map((id, _))
      case Timeout.FlexTimeout(id, minTimeout) =>
        Some(
          timeouts.get
            .get(id)
            .map(_.avgWithMin(minTimeout))
            .getOrElse(minTimeout)
        ).map((id, _))
      case Timeout.NoTimeout => None
    }
  }

  def getTimeout(timeout: Timeout): Option[FiniteDuration] =
    getNameAndTimeout(timeout).map(_._2)
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
