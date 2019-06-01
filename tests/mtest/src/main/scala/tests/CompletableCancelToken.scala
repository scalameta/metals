package tests

import java.util.concurrent.CompletableFuture
import scala.meta.pc.CancelToken
import java.util.concurrent.CancellationException

/** Cancel token that can be cancelled by calling `cancel()`. */
class CompletableCancelToken extends CancelToken {
  val onCancel = new CompletableFuture[java.lang.Boolean]()
  def cancel(): Unit = onCancel.complete(true)
  def isCancelled: Boolean = onCancel.getNow(false)
  def checkCanceled(): Unit = {
    if (isCancelled) {
      throw new CancellationException
    }
  }
}
