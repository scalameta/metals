package scala.meta.internal.metals

import java.lang
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.pc.CancelToken

/**
 * A cancel token backed by a Scala future.
 */
case class FutureCancelToken(f: Future[Unit])(implicit ec: ExecutionContext)
    extends CancelToken {
  var isCancelled: Boolean = false
  f.onComplete(_ => isCancelled = true)

  override def checkCanceled(): Unit = {
    if (isCancelled) {
      throw new ControlCancellationException()
    }
  }

  override def onCancel(): CompletionStage[lang.Boolean] =
    f.map(_ => java.lang.Boolean.TRUE).toJava
}
