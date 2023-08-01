package scala.meta.internal.pc

import scala.meta.pc.SyntheticDecoration

case class Decoration(
    start: Int,
    end: Int,
    text: String,
    kind: Int
) extends SyntheticDecoration