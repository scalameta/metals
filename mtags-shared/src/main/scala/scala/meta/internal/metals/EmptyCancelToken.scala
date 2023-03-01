package scala.meta.internal.metals

import java.lang
import java.util.concurrent.{CompletableFuture, CompletionStage}
import scala.meta.pc.CancelToken

object EmptyCancelToken extends CancelToken {
  override def checkCanceled(): Unit = ()

  override def onCancel(): CompletionStage[lang.Boolean] =
    CompletableFuture.completedFuture(lang.Boolean.FALSE)
}
