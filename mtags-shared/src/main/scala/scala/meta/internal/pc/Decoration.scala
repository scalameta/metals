package scala.meta.internal.pc

import scala.meta.pc.SyntheticDecoration
import scala.collection.JavaConverters._

import org.eclipse.{lsp4j => l}
import java.{util => ju}
import scala.meta.pc.InlayHintPart

case class Decoration(
    range: l.Range,
    labelParts: ju.List[InlayHintPart],
    kind: Int
) extends SyntheticDecoration

object Decoration {
  def apply(
      range: l.Range,
      label: String,
      kind: Int,
      symbol: Option[String] = None
  ): Decoration = {
    val symbolStr = symbol.getOrElse("")
    val labelPartWithData: InlayHintPart = LabelPart(label, symbolStr)
    new Decoration(range, List(labelPartWithData).asJava, kind)
  }

  def apply(
      range: l.Range,
      labelParts: List[LabelPart],
      kind: Int
  ): Decoration = {
    val inlayHintParts: List[InlayHintPart] = labelParts
    new Decoration(range, inlayHintParts.asJava, kind)
  }
}
