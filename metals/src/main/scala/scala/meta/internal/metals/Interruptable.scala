package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.Interruptable.CancelConnectException

class Interruptable[+T] private (
    futureIn: Future[T],
    cancelPromise: Promise[Unit],
) extends CompletableFuture {

  def future(implicit executor: ExecutionContext): Future[T] = futureIn.map(
    if (cancelPromise.isCompleted) throw CancelConnectException else _
  )

  override def cancel(mayInterruptIfRunning: Boolean): Boolean = {
    cancelPromise.trySuccess(())
    true
  }

  override def isCancelled(): Boolean = cancelPromise.isCompleted

  def flatMap[S](
      f: T => Interruptable[S]
  )(implicit executor: ExecutionContext): Interruptable[S] =
    new Interruptable(future.flatMap(f(_).future), cancelPromise)

  def map[S](
      f: T => S
  )(implicit executor: ExecutionContext): Interruptable[S] =
    new Interruptable(future.map(f(_)), cancelPromise)

  def recover[U >: T](
      pf: PartialFunction[Throwable, U]
  )(implicit executor: ExecutionContext): Interruptable[U] = {
    val pf0: PartialFunction[Throwable, U] = { case CancelConnectException =>
      throw CancelConnectException
    }
    new Interruptable(future.recover(pf0.orElse(pf)), cancelPromise)
  }
}

object Interruptable {
  def successful[T](result: T) =
    new Interruptable(Future.successful(result), Promise())

  object CancelConnectException extends RuntimeException
  implicit class XtensionFuture[+T](future: Future[T]) {
    def withInterrupt(cancelPromise: Promise[Unit]): Interruptable[T] =
      new Interruptable(future, cancelPromise)
  }
}
