package scala.meta.internal.bsp

sealed abstract class BuildChange extends Product with Serializable {
  def isNone: Boolean = this == BuildChange.None
  def isFailed: Boolean = this == BuildChange.Failed
  def isReconnected: Boolean = this == BuildChange.Reconnected
  def isReloaded: Boolean = this == BuildChange.Reloaded
}

object BuildChange {
  case object None extends BuildChange
  case object Failed extends BuildChange
  case object Reconnected extends BuildChange
  case object Reloaded extends BuildChange
  case object Cancelled extends BuildChange
}
