package scala.meta.internal.pc

import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionText

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.{lsp4j => l}

case class InlayHints(
    inlayHints: List[InlayHint],
    definitions: Set[Int]
) {
  def containsDef(offset: Int): Boolean = definitions(offset)
  def addDefinition(offset: Int): InlayHints =
    copy(
      definitions = definitions + offset
    )
  def add(
      pos: l.Range,
      labelParts: List[LabelPart],
      kind: InlayHintKind
  ): InlayHints =
    copy(inlayHints =
      addInlayHint(makeInlayHint(pos.getStart(), labelParts, kind))
    )

  private def makeInlayHint(
      pos: l.Position,
      labelParts: List[LabelPart],
      kind: InlayHintKind
  ) = {
    val hint = new InlayHint()
    hint.setPosition(pos)
    val (label, data) = labelParts.map(lp => (lp.label, getData(lp.data))).unzip
    hint.setLabel(label.asJava)
    hint.setData(data.toArray)
    hint.setKind(kind)
    hint
  }

  private def getData(data: Either[String, l.Position]): Any = {
    data match {
      case Left(str) => str
      case Right(pos) => pos
    }
  }

  // If method has both type parameter and implicit parameter, we want the type parameter decoration to be displayed first,
  // but it's added second. This method adds the decoration to the right position in the list.
  private def addInlayHint(inlayHint: InlayHint): List[InlayHint] = {
    val atSamePos =
      inlayHints.takeWhile(_.getPosition() == inlayHint.getPosition())
    (atSamePos :+ inlayHint) ++ inlayHints.drop(atSamePos.size)
  }
  def result(): List[InlayHint] = inlayHints.reverse

}

object InlayHints {
  def empty: InlayHints = InlayHints(Nil, Set.empty)

  def makeLabelParts(
      parts: List[LabelPart],
      tpeStr: String
  ): List[LabelPart] = {
    val buffer = ListBuffer.empty[LabelPart]
    var current = 0
    parts
      .flatMap { lp =>
        tpeStr.allIndexesWhere(lp.name).map((_, lp))
        // find all occurences of str in tpe
      }
      .sortWith { case ((idx1, lp1), (idx2, lp2)) =>
        if (idx1 == idx2) lp1.length > lp2.length
        else idx1 < idx2
      }
      .foreach { case (index, lp) =>
        if (index >= current) {
          buffer += LabelPart(tpeStr.substring(current, index))
          buffer += lp
          current = index + lp.length
        }
      }
    buffer += LabelPart(tpeStr.substring(current, tpeStr.length))
    buffer.toList.filter(_.name.nonEmpty)
  }
}
