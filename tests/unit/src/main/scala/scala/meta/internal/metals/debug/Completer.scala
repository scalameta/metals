package scala.meta.internal.metals.debug

import scala.concurrent.Future

import scala.meta.internal.metals.debug.DebugStep.Complete

import org.eclipse.lsp4j.debug.CompletionsResponse

final class Completer(expression: String) extends Stoppage.Handler {
  var response: CompletionsResponse = _

  override def apply(stoppage: Stoppage): DebugStep = {
    val frameId = stoppage.frame.info.getId
    val column = expression.indexOf("@@")
    require(column >= 0, "Expression needs @@ for testing completions")
    require(
      !expression.contains('\n'),
      "Only single line expression are supported currently"
    )
    Complete(expression.replace("@@", ""), frameId, response = _, 1, column + 1)
  }

  override def shutdown: Future[Unit] = Future.successful()
}
