package scala.meta.internal.metals

object DidFocusResult extends Enumeration {
  type DidFocusResult = Value
  val NoBuildTarget, RecentlyActive, AlreadyCompiled, Compiled = Value
}
