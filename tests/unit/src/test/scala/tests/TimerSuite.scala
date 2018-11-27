package tests

import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.Timer

object TimerSuite extends BaseSuite {
  def checkNanos(ms: Long, expected: String): Unit = {
    test(s"${ms}ms") {
      val obtained = Timer.readableNanos(TimeUnit.MILLISECONDS.toNanos(ms))
      assertNoDiff(obtained, expected)
    }
  }
  checkNanos(429, "0.43s")
  checkNanos(425, "0.42s")
  checkNanos(1429, "1.43s")
  checkNanos(10000, "10s")
  checkNanos(900, "0.9s")
}
