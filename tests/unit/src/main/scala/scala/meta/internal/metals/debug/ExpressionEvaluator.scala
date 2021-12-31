package scala.meta.internal.metals.debug

import scala.concurrent.Future

import scala.meta.internal.metals.debug.DebugStep.Continue
import scala.meta.internal.metals.debug.DebugStep.Evaluate

import org.eclipse.lsp4j.debug.EvaluateResponse

final class ExpressionEvaluator(expression: String) extends Stoppage.Handler {
  var response: EvaluateResponse = _

  override def apply(stoppage: Stoppage): DebugStep = {
    val frameId = stoppage.frame.info.getId
    Evaluate(expression, frameId, response = _, Continue)
  }

  override def shutdown: Future[Unit] = Future.successful(())
}
