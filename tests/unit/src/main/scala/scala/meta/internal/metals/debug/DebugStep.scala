package scala.meta.internal.metals.debug

sealed trait DebugStep
object DebugStep {
  case object Continue extends DebugStep
}
