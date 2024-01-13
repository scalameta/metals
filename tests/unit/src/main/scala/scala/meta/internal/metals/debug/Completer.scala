package scala.meta.internal.metals.debug

import scala.concurrent.Future

import scala.meta.internal.metals.debug.DebugStep.Complete

import org.eclipse.lsp4j.debug.CompletionsResponse

final class Completer(expression: String, isLineNullable: Boolean = false) extends Stoppage.Handler {
  var response: CompletionsResponse = _

  override def apply(stoppage: Stoppage): DebugStep = {
    val frameId = stoppage.frame.info.getId
    val cursorOffset = expression.indexOf("@@")
    val column = cursorOffset - expression
      .substring(0, cursorOffset)
      .lastIndexWhere(_ == '\n')
    val line =
      expression.substring(0, expression.indexOf("@@")).count(_ == '\n') + 1
    require(column >= 0, "Expression needs @@ for testing completions")
    Complete(
      expression.replace("@@", ""),
      frameId,
      response = _,
      line,
      column,
      isLineNullable
    )
  }

  override def shutdown: Future[Unit] = Future.successful(())
}
