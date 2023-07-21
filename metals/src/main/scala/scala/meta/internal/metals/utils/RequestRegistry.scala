package scala.meta.internal.metals.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable

class RequestRegistry(
    defaultMinTimeout: Duration,
    initialCancellables: List[Cancelable],
)(implicit
    ex: ExecutionContext
) {
  private val timeouts: Timeouts = new Timeouts(defaultMinTimeout)
  private val ongoingRequests =
    new MutableCancelable().addAll(initialCancellables)

  def register[T](
      action: () => CompletableFuture[T],
      timeout: Timeout = Timeout.DefaultFlexTimeout,
  ): CancelableFuture[T] = {
    val CancelableFuture(result, cancelable) = getTimeout(timeout) match {
      case Some(timeoutValue) =>
        FutureWithTimeout(timeoutValue)(action)
          .transform {
            case Success((res, time)) =>
              timeouts.measured(timeout, time)
              Success(res)
            case Failure(e: TimeoutException) =>
              timeouts.measured(timeout, timeoutValue)
              Failure(e)
            case Failure(e) => Failure(e)
          }
      case None =>
        val resultFuture = action()
        val cancelable = Cancelable { () =>
          Try(resultFuture.cancel(true))
        }
        CancelableFuture(resultFuture.asScala, cancelable)
    }

    ongoingRequests.add(cancelable)

    result.onComplete { _ => ongoingRequests.remove(cancelable) }

    CancelableFuture(result, cancelable)
  }

  def addOngoingRequest(values: Iterable[Cancelable]): MutableCancelable =
    ongoingRequests.addAll(values)

  def cancel(): Unit = {
    ongoingRequests.cancel()
  }

  def getTimeout(timeout: Timeout): Option[Duration] =
    timeouts.getTimeout(timeout)

}
