package scala.meta.internal.metals.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.Messages.RequestTimeout
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

class RequestRegistry(
    initialCancellables: List[Cancelable],
    languageClient: MetalsLanguageClient,
    requestTimeOutNotification: Option[DismissedNotifications#Notification] =
      None,
)(implicit
    ex: ExecutionContext
) {
  private val timeouts: Timeouts = new Timeouts()
  private val ongoingRequests =
    new MutableCancelable().addAll(initialCancellables)

  private def onTimeout(
      actionName: Option[String],
      cancelByDefault: Boolean,
  )(duration: Duration): Future[FutureWithTimeout.OnTimeout] =
    actionName match {
      case Some(actionName) if !cancelByDefault =>
        languageClient
          .showMessageRequest(
            RequestTimeout.params(actionName, duration.toMinutes.toInt),
            defaultTo = () => {
              languageClient.showMessage(
                RequestTimeout
                  .notificationParams(actionName, duration.toMinutes.toInt)
              )
              RequestTimeout.waitAction
            },
          )
          .asScala
          .map {
            case RequestTimeout.waitAction => FutureWithTimeout.Wait
            case RequestTimeout.cancel => FutureWithTimeout.Cancel
            case RequestTimeout.waitAlways =>
              requestTimeOutNotification.foreach(_.dismiss(7, TimeUnit.DAYS))
              FutureWithTimeout.Dismiss
            case _ => FutureWithTimeout.Dismiss
          }
      case _ => Future.successful(FutureWithTimeout.Cancel)
    }

  // sticky: set by `cancel()`, after which no request may be registered.
  // `@volatile` so a request being sent can re-read it without `lock`
  // (see scalameta/metals#3464).
  private val lock = new Object
  @volatile private var cancelled = false

  def isCancelled: Boolean = cancelled

  def register[T](
      action: () => CompletableFuture[T],
      timeout: Option[Timeout],
      cancelByDefault: Boolean = false,
  ): CancelableFuture[T] = {
    // Admission reserves a slot in `ongoingRequests` and fills it once the
    // request exists, so `lock` never spans the send: `action()` writes to the
    // build server socket and blocks for as long as a wedged server doesn't
    // read, and `cancel()` is teardown, which must not queue behind that write.
    val slot = new MutableCancelable
    val admitted = lock.synchronized {
      if (cancelled) false
      else {
        ongoingRequests.add(slot)
        true
      }
    }
    if (admitted) registerOpen(action, timeout, cancelByDefault, slot)
    else
      CancelableFuture(
        Future.failed(
          new IllegalStateException("the connection is already closed")
        ),
        Cancelable.empty,
      )
  }

  private def registerOpen[T](
      action: () => CompletableFuture[T],
      timeout: Option[Timeout],
      cancelByDefault: Boolean,
      slot: MutableCancelable,
  ): CancelableFuture[T] = {
    val CancelableFuture(result, cancelable) =
      try sendRequest(action, timeout, cancelByDefault)
      catch {
        case NonFatal(e) =>
          // the slot was reserved before the send, it must not outlive it
          ongoingRequests.remove(slot)
          throw e
      }

    slot.add(cancelable)
    // A drain that ran while the request was being sent found the slot empty,
    // so the request cancels itself here; one that runs after this point finds
    // the cancelable in the slot. Cancelling twice is harmless.
    if (cancelled) slot.cancel()

    result.onComplete { _ => ongoingRequests.remove(slot) }

    CancelableFuture(result, cancelable)
  }

  private def sendRequest[T](
      action: () => CompletableFuture[T],
      timeout: Option[Timeout],
      cancelByDefault: Boolean,
  ): CancelableFuture[T] = {
    val CancelableFuture(result, cancelable) =
      timeout match {
        case Some(timeout)
            if !requestTimeOutNotification.exists(_.isDismissed) =>
          val timeoutValue = timeouts.getTimeout(timeout)
          FutureWithTimeout(
            timeoutValue,
            onTimeout(timeout.name, cancelByDefault)(_),
          )(action)
            .transform {
              case Success((res, time)) =>
                timeouts.measured(timeout, time)
                Success(res)
              case Failure(e: TimeoutException) =>
                timeouts.measured(timeout, timeoutValue)
                Failure(e)
              case Failure(e) => Failure(e)
            }
        case _ =>
          val resultFuture = action()
          val cancelable = Cancelable { () =>
            Try(resultFuture.cancel(true))
          }
          CancelableFuture(resultFuture.asScala, cancelable)
      }

    CancelableFuture(result, cancelable)
  }

  def addOngoingRequest(values: Iterable[Cancelable]): MutableCancelable =
    ongoingRequests.addAll(values)

  def cancel(): Unit = {
    // `lock` here only orders the flag against admission, and is never held
    // across a request send, so teardown cannot stall behind one
    lock.synchronized { cancelled = true }
    ongoingRequests.cancel()
  }

  def getTimeout(timeout: Timeout): Duration = timeouts.getTimeout(timeout)

}
