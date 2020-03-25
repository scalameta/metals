package scala.meta.internal.metals

import scala.concurrent.{ExecutionContext, Future}

case class CancelableFuture[T](
    future: Future[T],
    cancelable: Cancelable = Cancelable.empty
) extends Cancelable {
  def merge[U](
      other: CancelableFuture[U]
  )(implicit ec: ExecutionContext): CancelableFuture[(T, U)] =
    CancelableFuture(
      future.flatMap(t => other.future.map(u => (t, u))),
      new MutableCancelable().addAll(Seq(cancelable, other.cancelable))
    )
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
