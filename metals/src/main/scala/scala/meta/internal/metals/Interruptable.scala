package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.Interruptable.CancelConnectException

class Interruptable[+T] private (
    futureIn: Future[T],
    val cancelable: Cancelable,
) {

  def future(implicit
      executor: ExecutionContext,
      cancelPromise: CancelSwitch,
  ): Future[T] = futureIn.map(
    if (cancelPromise.promise.isCompleted) throw CancelConnectException else _
  )

  def flatMap[S](
      f: T => Interruptable[S]
  )(implicit
      executor: ExecutionContext,
      cancelPromise: CancelSwitch,
  ): Interruptable[S] = {
    val mutCancel =
      cancelable match {
        case c: MutableCancelable => c
        case c => new MutableCancelable().add(c)
      }
    val newFuture = future.flatMap { res =>
      val i = f(res)
      mutCancel.add(i.cancelable)
      i.future
    }
    new Interruptable(newFuture, mutCancel)
  }

  def map[S](
      f: T => S
  )(implicit
      executor: ExecutionContext,
      cancelPromise: CancelSwitch,
  ): Interruptable[S] =
    new Interruptable(future.map(f(_)), cancelable)

  def recover[U >: T](
      pf: PartialFunction[Throwable, U]
  )(implicit
      executor: ExecutionContext,
      cancelPromise: CancelSwitch,
  ): Interruptable[U] = {
    val pf0: PartialFunction[Throwable, U] = { case CancelConnectException =>
      throw CancelConnectException
    }
    new Interruptable(future.recover(pf0.orElse(pf)), cancelable)
  }

  def toCancellable(implicit cancelPromise: CancelSwitch): CancelableFuture[T] =
    CancelableFuture(
      futureIn,
      () => { cancelPromise.promise.trySuccess(()); cancelable.cancel() },
    )
}

object Interruptable {
  def successful[T](result: T) =
    new Interruptable(Future.successful(result), Cancelable.empty)

  object CancelConnectException extends RuntimeException
  implicit class XtensionFuture[+T](future: Future[T]) {
    def withInterrupt: Interruptable[T] =
      new Interruptable(future, Cancelable.empty)
  }

  implicit class XtensionCancelFuture[+T](future: CancelableFuture[T]) {
    def withInterrupt: Interruptable[T] =
      new Interruptable(future.future, future.cancelable)
  }
}

case class CancelSwitch(promise: Promise[Unit]) extends AnyVal
