package tests

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.utils.FutureWithTimeout
import scala.meta.internal.metals.utils.Timeout
import scala.meta.internal.metals.utils.Timeouts

import munit.FunSuite

class TimeoutSuite extends FunSuite {
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  val duration: FiniteDuration = Duration(1, TimeUnit.SECONDS)
  val onTimeout: Duration => Future[FutureWithTimeout.OnTimeout] = _ =>
    Future.successful(FutureWithTimeout.Cancel)

  test("simple-timeout") {
    val promise = Promise[Unit]()
    val f = FutureWithTimeout(duration, onTimeout)(
      ExampleFutures.infinite(promise)
    ).future
    assert(!f.isCompleted)

    for {
      _ <- f.failed.map {
        case _: TimeoutException =>
        case _ =>
          throw new RuntimeException(
            "future should have thrown TimeoutException"
          )
      }
      _ <- promise.future
    } yield ()
  }

  test("no-timeout") {
    FutureWithTimeout(duration, onTimeout)(ExampleFutures.fast).future.map {
      case (res, time) =>
        assert(time.toMillis >= ExampleFutures.timeStep)
        assertEquals(res, 1)
    }
  }

  test("timeouts") {
    def min(int: Int) = Duration(int, TimeUnit.MINUTES)
    val timeouts = new Timeouts(min(3))
    val flexTimeout = Timeout.FlexTimeout("flex", min(6))
    assertEquals(timeouts.getTimeout(Timeout.NoTimeout), None)
    assertEquals(
      timeouts.getTimeout(Timeout.DefaultFlexTimeout("request")),
      Some(min(3)),
    )
    assertEquals(timeouts.getTimeout(flexTimeout), Some(min(6)))
    timeouts.measured(Timeout.DefaultFlexTimeout("request"), min(1))
    timeouts.measured(Timeout.DefaultFlexTimeout("request"), min(3))
    timeouts.measured(flexTimeout, min(1))
    timeouts.measured(flexTimeout, min(2))
    // avg * 3 > min
    assertEquals(
      timeouts.getTimeout(Timeout.DefaultFlexTimeout("request")),
      Some(min(6)),
    )
    // avg * 3 < min
    assertEquals(timeouts.getTimeout(flexTimeout), Some(min(6)))
  }
}

object ExampleFutures {
  val timeStep = 500 // 1/2 sec

  def infinite(
      promise: Promise[Unit]
  )(implicit ec: ExecutionContext): () => CompletableFuture[Void] = () => {
    val future =
      CompletableFuture.runAsync(
        new Runnable {
          def run() = while (!promise.isCompleted) {
            Thread.sleep(timeStep)
          }
        }
      )
    future.asScala.onComplete(_ => promise.trySuccess(()))
    future
  }

  def fast(implicit ex: ExecutionContext): () => CompletableFuture[Int] = () =>
    Future {
      Thread.sleep(timeStep)
      1
    }.asJava

  def done: () => CompletableFuture[Int] = () =>
    CompletableFuture.completedFuture(2)

  def fromScala(implicit ex: ExecutionContext): () => CompletableFuture[Unit] =
    () =>
      Future {
        Thread.sleep(1500)
      }.asJava
}
