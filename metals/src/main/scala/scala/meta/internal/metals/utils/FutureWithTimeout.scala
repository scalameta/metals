package scala.meta.internal.metals.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.javaapi.FutureConverters
import scala.util.Failure
import scala.util.Success

object FutureWithTimeout {
  def apply[T](timeout: Long)(
      future: => CompletableFuture[T]
  )(implicit ex: ExecutionContext): (CompletableFuture[T], Future[Long]) = {
    val timer = new CancellableTimer[T](timeout)
    val timeBefore = System.currentTimeMillis()
    val timeFuture = Future
      .firstCompletedOf(List(timer.future, FutureConverters.asScala(future)))
      .transform {
        case Failure(e: TimeoutException) =>
          future.completeExceptionally(e)
          Success(timeout)
        case _ =>
          val timeAfter = System.currentTimeMillis()
          timer.cancel()
          Success(timeAfter - timeBefore)
      }
    (future, timeFuture)
  }
}

class CancellableTimer[T](time: Long) {
  // 1 sec
  private val step = 1000
  private val maxSec = time / step
  private var i: Long = 0
  def future(implicit ex: ExecutionContext): Future[T] = Future {
    while (i < maxSec) {
      Thread.sleep(step)
      i += 1
    }
    throw new TimeoutException
  }
  def cancel(): Unit = i = maxSec
}
