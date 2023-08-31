package scala.meta.internal.mtags

object UnresolvedOverriddenSymbol {
  def apply(name: String, pos: Int): String =
    s"unresolved::$name::$pos"

  def unapply(unresolved: String): Option[(String, Int)] =
    unresolved match {
      case s"unresolved::$name::$pos" => pos.toIntOption.map((name, _))
      case _ => None
    }
}
