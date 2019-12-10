package scala.meta.internal.metals.debug

import scala.collection.mutable
import scala.concurrent.Future
import scala.meta.internal.metals.debug.Stoppage.Handler

final class StackFrameCollector extends Handler {
  private val stackFrames = mutable.Buffer.empty[StackFrame]

  override def apply(stoppage: Stoppage): Future[DebugStep] = {
    stackFrames += stoppage.frame
    Future.successful(DebugStep.Continue)
  }

  def variables: List[Variables] = this.stackFrames.map(_.variables).toList

  override def shutdown: Future[Unit] = Future.unit
}
