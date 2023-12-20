package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class CancelableFuture[T](
    future: Future[T],
    cancelable: Cancelable = Cancelable.empty,
) extends Cancelable {
  def map[U](f: T => U)(implicit ec: ExecutionContext): CancelableFuture[U] =
    CancelableFuture(future.map(f), cancelable)
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
    CancelableFuture(Future.successful(value), Cancelable.empty)
  def sequence[T](
      futures: Seq[CancelableFuture[T]]
  )(implicit ec: ExecutionContext): CancelableFuture[Seq[T]] =
    CancelableFuture(
      Future.sequence(futures.map(_.future)),
      new MutableCancelable().addAll(futures.map(_.cancelable)),
    )
}
