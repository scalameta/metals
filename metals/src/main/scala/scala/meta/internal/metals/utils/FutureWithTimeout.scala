package scala.meta.internal.metals.utils

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object FutureWithTimeout {
  def apply[T](timeout: Long)(
      future: => Future[T]
  )(implicit ex: ExecutionContext): Future[(T, Long)] = Future {
    val timeBefore = System.currentTimeMillis()
    val res = Await.result(future, Duration(timeout, TimeUnit.MILLISECONDS))
    val timeAfter = System.currentTimeMillis()
    (res, timeAfter - timeBefore)
  }
}
