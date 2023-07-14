package scala.meta.internal.metals.utils

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object FutureWithTimeout {
  def apply[T](duration: Duration)(
      future: Future[T],
      timeBefore: Long = System.currentTimeMillis(),
  )(implicit ex: ExecutionContext): Future[(T, Duration)] = Future {
    val res = Await.result(future, duration)
    val timeAfter = System.currentTimeMillis()
    val execTime = timeAfter - timeBefore
    (res, Duration(execTime, TimeUnit.MILLISECONDS))
  }
}
