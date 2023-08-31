package scala.meta.internal.mtags

sealed trait OverriddenSymbol
case class UnresolvedOverriddenSymbol(name: String, pos: Int)
    extends OverriddenSymbol
case class ResolvedOverriddenSymbol(symbol: String) extends OverriddenSymbol
