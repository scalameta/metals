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

  def register[T](
      action: () => CompletableFuture[T],
      timeout: Option[Timeout],
      cancelByDefault: Boolean = false,
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

    ongoingRequests.add(cancelable)

    result.onComplete { _ => ongoingRequests.remove(cancelable) }

    CancelableFuture(result, cancelable)
  }

  def addOngoingRequest(values: Iterable[Cancelable]): MutableCancelable =
    ongoingRequests.addAll(values)

  def cancel(): Unit = {
    ongoingRequests.cancel()
  }

  def getTimeout(timeout: Timeout): Duration = timeouts.getTimeout(timeout)

}
