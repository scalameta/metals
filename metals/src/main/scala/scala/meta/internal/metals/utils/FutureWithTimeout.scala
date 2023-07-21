package scala.meta.internal.metals.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Try

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.MetalsEnrichments._

object FutureWithTimeout {
  def apply[T](duration: Duration)(
      future: () => CompletableFuture[T]
  )(implicit ex: ExecutionContext): CancelableFuture[(T, Duration)] = {
    val timeBefore: Long = System.currentTimeMillis()
    val resultFuture = future()
    val cancelable = Cancelable { () =>
      Try(resultFuture.cancel(true))
    }
    val withTimeout =
      Future {
        val res = Await.result(resultFuture.asScala, duration)
        val timeAfter = System.currentTimeMillis()
        val execTime = timeAfter - timeBefore
        (res, Duration(execTime, TimeUnit.MILLISECONDS))
      }

    withTimeout.onComplete {
      case Failure(_: TimeoutException) => cancelable.cancel()
      case _ =>
    }
    CancelableFuture(withTimeout, cancelable)
  }
}
