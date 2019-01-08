package tests

import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.Timer

object TimerSuite extends BaseSuite {
  def checkMillis(ms: Long, expected: String): Unit = {
    checkNanos(TimeUnit.MILLISECONDS.toNanos(ms), expected)
  }
  def checkNanos(ns: Long, expected: String): Unit = {
    test(s"${ns}ns") {
      val obtained = Timer.readableNanos(ns)
      assertNoDiff(obtained, expected)
    }
  }

  checkMillis(0, "0ns")
  checkNanos(100, "100ns")
  checkNanos(500, "500ns")
  checkNanos(5000, "5μs")
  checkMillis(1, "1ms")
  checkMillis(10, "10ms")
  checkMillis(42, "42ms")
  checkMillis(60, "0.06s")
  checkMillis(429, "0.43s")
  checkMillis(425, "0.42s")
  checkMillis(1429, "1.43s")
  checkMillis(10000, "10s")
  checkMillis(900, "0.9s")
}
