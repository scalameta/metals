package scala.meta.internal.metals

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit

class Timer(time: Time) {
  val startNanos: Long = time.nanos()
  def isLogWorthy: Boolean =
    elapsedMillis > 100
  def isHumanVisible: Boolean =
    elapsedMillis > 200
  def elapsedNanos: Long = {
    val now = time.nanos()
    now - startNanos
  }
  def elapsedMillis: Long = {
    TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
  }
  def elapsedSeconds: Long = {
    TimeUnit.NANOSECONDS.toSeconds(elapsedNanos)
  }
  override def toString: String = {
    Timer.readableNanos(elapsedNanos)
  }
}

object Timer {
  def readableNanos(nanos: Long): String = {
    val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
    if (seconds > 5) readableSeconds(seconds)
    else {
      val ms = TimeUnit.NANOSECONDS.toMillis(nanos)
      if (ms < 1) {
        val micros = TimeUnit.NANOSECONDS.toMicros(nanos)
        if (micros < 1) {
          s"${nanos}ns"
        } else {
          s"${micros}μs"
        }
      } else if (ms < 100) {
        s"${ms}ms"
      } else {
        val partialSeconds = ms.toDouble / 1000
        new DecimalFormat("#.##s", new DecimalFormatSymbols(Locale.US))
          .format(partialSeconds)
      }
    }
  }
  def readableSeconds(n: Long): String = {
    val minutes = n / 60
    val seconds = n % 60
    if (minutes > 0) {
      if (seconds == 0) s"${minutes}m"
      else s"${minutes}m${seconds}s"
    } else {
      s"${seconds}s"
    }
  }
}
