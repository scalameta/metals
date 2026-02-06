package scala.meta.internal.metals.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.MetalsEnrichments._

object FutureWithTimeout {

  def apply[T](
      duration: FiniteDuration,
      onTimeout: FiniteDuration => Future[FutureWithTimeout.OnTimeout],
  )(
      future: () => CompletableFuture[T]
  )(implicit ex: ExecutionContext): CancelableFuture[(T, FiniteDuration)] = {
    val result = Promise[(T, FiniteDuration)]()
    val timeBefore: Long = System.currentTimeMillis()

    val javaRequest = future()
    val request = javaRequest.asScala.map { res =>
      val timeAfter = System.currentTimeMillis()
      val execTime = timeAfter - timeBefore
      (res, FiniteDuration(execTime, TimeUnit.MILLISECONDS))
    }
    val cancelable = new Cancelable {
      override def cancel(): Unit = Try(javaRequest.cancel(true))
    }

    request.onComplete(result.tryComplete)

    def withOnTimeout(
        withTimeout: Future[(T, FiniteDuration)],
        duration: FiniteDuration,
        reason: Option[String],
    ): Future[(T, FiniteDuration)] = {
      withTimeout.transformWith {
        case Success(res) => Future.successful(res)
        case Failure(e: TimeoutException) =>
          for {
            action <- onTimeout(duration)
            res <- action match {
              case FutureWithTimeout.Cancel =>
                result.tryFailure(e)
                cancelable.cancel()
                Future.failed(e)
              case FutureWithTimeout.Wait =>
                withOnTimeout(
                  request.withTimeout(duration * 3, reason),
                  duration * 3,
                  reason,
                )
              case FutureWithTimeout.Dismiss => request
            }
          } yield res
        case Failure(e) => Future.failed(e)
      }
    }

    withOnTimeout(
      request.withTimeout(duration, reason = None),
      duration,
      reason = None,
    )

    CancelableFuture(result.future, cancelable)
  }

  sealed trait OnTimeout
  case object Wait extends OnTimeout
  case object Cancel extends OnTimeout
  case object Dismiss extends OnTimeout
}
