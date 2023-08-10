package scala.meta.internal.pc

import scala.meta.pc.InlayHintPart

case class LabelPart(
    label: String,
    symbol: String = ""
) extends InlayHintPart
