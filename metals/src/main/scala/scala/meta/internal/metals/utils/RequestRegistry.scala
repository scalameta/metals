package scala.meta.internal.metals.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.Cancelable
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
  private val ongoingCompilations = new MutableCancelable()

  def register[T](
      action: () => CompletableFuture[T],
      timeout: Timeout = Timeout.DefaultFlexTimeout,
      isCompile: Boolean = false,
  ): Future[T] = {
    val timeBefore: Long = System.currentTimeMillis()
    val resultFuture = action()
    val cancelable = Cancelable { () =>
      Try(resultFuture.cancel(true))
    }

    if (isCompile) ongoingCompilations.add(cancelable)
    else ongoingRequests.add(cancelable)

    val result = getTimeout(timeout) match {
      case Some(timeoutValue) =>
        FutureWithTimeout(timeoutValue)(resultFuture.asScala, timeBefore)
          .transform {
            case Success((res, time)) =>
              timeouts.measured(timeout, time)
              Success(res)
            case Failure(e: TimeoutException) =>
              timeouts.measured(timeout, timeoutValue)
              Failure(e)
            case Failure(e) => Failure(e)
          }
      case None => resultFuture.asScala
    }
    result.onComplete { _ =>
      if (isCompile) ongoingCompilations.remove(cancelable)
      else ongoingRequests.remove(cancelable)
    }
    result
  }

  def addOngoingRequest(values: Iterable[Cancelable]): MutableCancelable =
    ongoingRequests.addAll(values)

  def addOngoingCompilations(values: Iterable[Cancelable]): MutableCancelable =
    ongoingCompilations.addAll(values)

  def cancelCompilations(): Unit = {
    ongoingCompilations.cancel()
  }

  def cancel(): Unit = {
    ongoingCompilations.cancel()
    ongoingRequests.cancel()
  }

  def getTimeout(timeout: Timeout): Option[Duration] =
    timeouts.getTimeout(timeout)

}
