package scala.meta.internal.metals

import scala.meta.trees.Origin

object ScalametaCompat:
  object ParsedOrigin:
    def unapply(origin: Origin): Option[(Int, Int)] = origin match
      case Origin.Parsed(_, start, end) => Some((start, end))
      case _ => None
