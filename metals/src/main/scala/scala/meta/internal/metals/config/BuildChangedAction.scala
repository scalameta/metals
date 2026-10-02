package scala.meta.internal.metals.config

sealed trait BuildChangedAction
object BuildChangedAction {
  case object None extends BuildChangedAction
  case object Prompt extends BuildChangedAction
}
