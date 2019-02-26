package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.pc.CancelToken
import scala.util.Failure
import scala.util.Success

object CancelTokens {
  def apply[T](
      fn: CancelToken => T
  )(implicit ec: ExecutionContextExecutorService): CompletableFuture[T] = {
    future[T](token => Future(fn(token)))
  }
  def future[T](
      fn: CancelToken => Future[T]
  )(implicit ec: ExecutionContextExecutorService): CompletableFuture[T] = {
    val token = Promise[Unit]()
    val result = new CompletableFuture[T]() {
      override def cancel(mayInterruptIfRunning: Boolean): Boolean = {
        token.trySuccess(())
        super.cancel(mayInterruptIfRunning)
      }
    }
    fn(FutureCancelToken(token.future)).onComplete {
      case Failure(exception) =>
        result.completeExceptionally(exception)
      case Success(value) =>
        result.complete(value)
    }
    result
  }
}
