package scala.meta.internal.metals

sealed abstract class BuildChange extends Product with Serializable {
  def isNone: Boolean = this == BuildChange.None
  def isFailed: Boolean = this == BuildChange.Failed
  def isReconnected: Boolean = this == BuildChange.Reconnected
}

object BuildChange {
  case object None extends BuildChange
  case object Failed extends BuildChange
  case object Reconnected extends BuildChange
}
