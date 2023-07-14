package tests

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.utils.FutureWithTimeout
import scala.meta.internal.metals.utils.Timeout
import scala.meta.internal.metals.utils.Timeouts

import munit.FunSuite

class TimeoutSuite extends FunSuite {
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  val duration: FiniteDuration = Duration(1, TimeUnit.SECONDS)

  test("simple-timeout") {
    val f = FutureWithTimeout(duration)(ExampleFutures.infinite)
    assert(!f.isCompleted)
    f.failed.map {
      case _: TimeoutException =>
      case _ =>
        throw new RuntimeException("future should have thrown TimeoutException")
    }
  }

  test("no-timeout") {
    val timeBefore = System.currentTimeMillis()
    val future = ExampleFutures.fast
    FutureWithTimeout(duration)(future, timeBefore).map { case (res, time) =>
      assert(time.toMillis >= ExampleFutures.timeStep)
      assertEquals(res, 1)
    }
  }

  test("timeouts") {
    def min(int: Int) = Duration(int, TimeUnit.MINUTES)
    val timeouts = new Timeouts(min(3))
    val flexTimeout = Timeout.FlexTimeout("flex", min(6))
    assertEquals(timeouts.getTimeout(Timeout.NoTimeout), None)
    assertEquals(timeouts.getTimeout(Timeout.DefaultFlexTimeout), Some(min(3)))
    assertEquals(timeouts.getTimeout(flexTimeout), Some(min(6)))
    timeouts.measured(Timeout.DefaultFlexTimeout, min(1))
    timeouts.measured(Timeout.DefaultFlexTimeout, min(3))
    timeouts.measured(flexTimeout, min(1))
    timeouts.measured(flexTimeout, min(2))
    // avg * 3 > min
    assertEquals(timeouts.getTimeout(Timeout.DefaultFlexTimeout), Some(min(6)))
    // avg * 3 < min
    assertEquals(timeouts.getTimeout(flexTimeout), Some(min(6)))
  }
}

object ExampleFutures {
  val timeStep = 500 // 1/2 sec

  def infinite(implicit ex: ExecutionContext): Future[Unit] = Future {
    while (0 == 0) { Thread.sleep(timeStep) }
  }

  def fast(implicit ex: ExecutionContext): Future[Int] = Future {
    Thread.sleep(timeStep)
    1
  }
}
