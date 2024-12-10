package scala.meta.internal.pc

import java.net.URI

import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionText

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.{lsp4j => l}

case class InlayHints(
    uri: URI,
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
    val (label, dataInfo) = labelParts.map(lp => (lp.label, lp.data)).unzip
    hint.setLabel(label.asJava)
    hint.setData(InlayHints.toData(uri, dataInfo))
    hint.setKind(kind)
    hint
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
  private val gson = new Gson()
  def empty(uri: URI): InlayHints = InlayHints(uri, Nil, Set.empty)

  /**
   * Creates a label for inlay hint by inserting `parts` on correct positions in `tpeStr`.
   *
   * @param parts each contain a single symbol name and its definition position or semanticdb symbol
   * @param tpeStr correct label for the inlay hint
   *
   * Example: for `tpeStr` = `(Int, List[Int])`,
   * `parts` are `List(("Int", "scala/Int#"), ("List", "scala/collection/immutable/List#"), ("Int", "scala/Int#"))`
   */
  def makeLabelParts(
      parts: List[LabelPart],
      tpeStr: String
  ): List[LabelPart] = {
    val buffer = ListBuffer.empty[LabelPart]
    var current = 0
    parts
      .flatMap { lp =>
        tpeStr.allIndexesOf(lp.name).map((_, lp))
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

  def toData(uri: URI, data: List[Either[String, l.Position]]): JsonElement =
    gson.toJsonTree(
      InlineHintData(
        uri,
        data.map {
          case Left(str) => LabelPartData("string", str, null)
          case Right(pos) => LabelPartData("position", null, pos)
        }.asJava
      )
    )

  def fromData(json: JsonElement): (URI, List[Either[String, l.Position]]) = {
    val data = gson.fromJson(json, classOf[InlineHintData])
    (
      data.uri,
      data.labelParts.asScala.toList.map { part =>
        part.dataType match {
          case "position" => Right(part.position)
          case "string" => Left(part.string)
        }
      }
    )
  }
}

final case class InlineHintData(
    uri: URI,
    labelParts: java.util.List[LabelPartData]
)

// "string" or "position"
final case class LabelPartData(
    dataType: String,
    string: String,
    position: l.Position
)
