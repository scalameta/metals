package scala.meta.internal.metals.debug

sealed trait DebugStep
object DebugStep {
  case object Continue extends DebugStep
  case object StepIn extends DebugStep
  case object StepOut extends DebugStep
  case object StepOver extends DebugStep
}
