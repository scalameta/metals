package scala.meta.internal.metals

import java.util.concurrent.TimeUnit

/**
 * Wrapper around `System.nanoTime` to allow easier testing.
 */
trait Time {
  def nanos(): Long
  final def millis(): Long = TimeUnit.NANOSECONDS.toMillis(nanos())
}

object Time {
  object system extends Time {
    def nanos(): Long = System.nanoTime()
  }
}
