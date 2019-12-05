package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.pc.CancelToken
import scala.util.Failure
import scala.util.Success

/**
 * Constructs an async `CancelToken`.
 *
 * The `CancelChecker` API in lsp4j is un-sufficient for our needs because
 * we want to get a `Future[Boolean]` that completes to `true` when the user
 * cancels a request, allowing us to abort expensive computation like typechecking
 * as soon as possible. With `CancelChecker`, we need to explicitly call
 * `token.checkCancelled()`, which is not possible  inside the compiler.
 */
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
    fn(FutureCancelToken.fromUnit(token.future)).onComplete {
      case Failure(exception) =>
        result.completeExceptionally(exception)
      case Success(value) =>
        result.complete(value)
    }
    // NOTE(olafur): we cannot use `Future.asJava` or `CompletableFuture.handleAsync`
    // since those methods return a `CompletableFuture` that doesn't contain the
    // custom `override def cancel()` above.
    result
  }
}
