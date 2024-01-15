package scala.meta.internal.metals.debug

import org.eclipse.lsp4j.debug.CompletionsResponse
import org.eclipse.lsp4j.debug.EvaluateResponse

sealed trait DebugStep
object DebugStep {
  case object Continue extends DebugStep
  case object StepIn extends DebugStep
  case object StepOut extends DebugStep
  case object StepOver extends DebugStep
  case class Evaluate(
      expression: String,
      frameId: Int,
      callback: EvaluateResponse => Unit,
      nextStep: DebugStep,
  ) extends DebugStep
  case class Complete(
      expression: String,
      frameId: Int,
      callback: CompletionsResponse => Unit,
      line: Int,
      character: Int,
      isLineNullable: Boolean = false,
  ) extends DebugStep
}
