package scala.meta.internal.metals

import scala.meta.internal.trees.Origin

object ScalametaCompat {
  object ParsedOrigin {
    def unapply(origin: Origin): Option[(Int, Int)] = origin match {
      case parsed: Origin.Parsed =>
        Some((parsed.pos.start, parsed.pos.end))
      case _ => None
    }
  }
}
