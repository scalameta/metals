package scala.meta.internal.metals

import java.lang
import java.util.concurrent.CompletionStage
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.pc.CancelToken
import scala.compat.java8.FutureConverters._
import scala.util.Success
import scala.util.Failure

/**
 * A cancel token backed by a Scala future.
 */
case class FutureCancelToken(f: Future[Boolean])(implicit ec: ExecutionContext)
    extends CancelToken {
  var isCancelled: Boolean = false
  f.onComplete {
    case Failure(exception) =>
      isCancelled = true
    case Success(cancel) =>
      isCancelled = cancel
  }

  override def checkCanceled(): Unit = {
    if (isCancelled) {
      throw new InterruptedException()
    }
  }

  override def onCancel(): CompletionStage[lang.Boolean] =
    f.map(cancel => java.lang.Boolean.valueOf(cancel)).toJava
}

object FutureCancelToken {
  def fromUnit(
      f: Future[Unit]
  )(implicit ec: ExecutionContext): FutureCancelToken =
    FutureCancelToken(f.map(_ => true))
}
