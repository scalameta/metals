package tests

import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.Time

class FakeTime extends Time {
  private var elapsed = 0L
  def reset(): Unit = elapsed = 0
  def elapse(count: Long, unit: TimeUnit): Unit = {
    elapsed += unit.toNanos(count)
  }
  def elapseSeconds(n: Int): Unit = {
    elapse(n, TimeUnit.SECONDS)
  }
  override def nanos(): Long = elapsed
}
