package tests

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.utils.RequestRegistry
import scala.meta.internal.metals.utils.Timeout

import munit.FunSuite

class RequestRegistrySuite extends FunSuite {
  implicit val ex: ExecutionContextExecutor = ExecutionContext.global
  val duration: FiniteDuration = Duration(1, TimeUnit.SECONDS)
  def createRegistry() = new RequestRegistry(duration, List())

  test("avg") {
    val requestRegistry = createRegistry()
    val doneTimeout = Timeout.FlexTimeout("done", duration)
    for {
      _ <- requestRegistry.register(
        Examples.done,
        doneTimeout,
      )
      _ <- requestRegistry.register(
        Examples.done,
        doneTimeout,
      )
      _ <- requestRegistry.register(
        Examples.done,
        doneTimeout,
      )
      _ <- requestRegistry.register(Examples.fast)
      _ <- requestRegistry.register(Examples.fast)
      _ <- requestRegistry.register(Examples.fast)
      _ = assert(
        requestRegistry.getTimeout(Timeout.DefaultFlexTimeout).get > duration
      )
      _ = assert(
        requestRegistry
          .getTimeout(Timeout.DefaultFlexTimeout)
          .get < duration * 2
      )
      _ = assert(requestRegistry.getTimeout(doneTimeout).get == duration)
    } yield ()
  }

  test("timeout") {
    val requestRegistry = createRegistry()
    for {
      err <- requestRegistry
        .register(Examples.infinite)
        .failed
      _ = assert(err.isInstanceOf[TimeoutException])
      _ = assertEquals(
        requestRegistry.getTimeout(Timeout.DefaultFlexTimeout).get,
        duration * 3,
      )
      _ <- requestRegistry.register(Examples.fast)
    } yield ()
  }

  test("cancel") {
    val requestRegistry = createRegistry()
    val f1 = requestRegistry.register(Examples.fast, isCompile = true)
    val f2 = requestRegistry.register(Examples.fast)
    requestRegistry.cancelCompilations()
    for {
      err <- f1.failed
      _ = assert(err.isInstanceOf[CancellationException])
      _ <- f2
    } yield ()
  }

  test("cancel-all") {
    val requestRegistry = createRegistry()
    val f1 = requestRegistry.register(Examples.fast, isCompile = true)
    val f2 = requestRegistry.register(Examples.fast)
    requestRegistry.cancel()
    for {
      err <- f1.failed
      _ = assert(err.isInstanceOf[CancellationException])
      err <- f2.failed
      _ = assert(err.isInstanceOf[CancellationException])
    } yield ()
  }

}

object RequestType extends Enumeration {
  val Type1, Type2 = Value
}

object Examples {
  def infinite: () => CompletableFuture[Void] = () =>
    CompletableFuture.runAsync(
      new Runnable {
        def run() = while (0 == 0) { Thread.sleep(500) }
      }
    )

  def fast: () => CompletableFuture[Void] = () =>
    CompletableFuture.runAsync(
      new Runnable {
        def run() = Thread.sleep(500)
      }
    )

  def done: () => CompletableFuture[Int] = () =>
    CompletableFuture.completedFuture(2)
}
