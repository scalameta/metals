package scala.meta.internal.metals

import scala.concurrent.{ExecutionContext, Future}

case class CancelableFuture[T](
    future: Future[T],
    cancelable: Cancelable = Cancelable.empty
) extends Cancelable {
  def cancel(): Unit = {
    cancelable.cancel()
  }
}

object CancelableFuture {
  def apply[T](
      thunk: => T
  )(implicit ec: ExecutionContext): CancelableFuture[T] = {
    CancelableFuture(Future(thunk), Cancelable.empty)
  }
  def successful[T](value: T): CancelableFuture[T] =
    CancelableFuture(Future.successful(value))
}
