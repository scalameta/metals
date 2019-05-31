package scala.meta.internal.metals

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

case class CancelableFuture[T](
    future: Future[T],
    cancelable: Cancelable = Cancelable.empty
)

object CancelableFuture {
  def apply[T](
      thunk: => T
  )(implicit ec: ExecutionContext): CancelableFuture[T] = {
    CancelableFuture(Future(thunk), Cancelable.empty)
  }
  def successful[T](value: T): CancelableFuture[T] =
    CancelableFuture(Future.successful(value))
}
