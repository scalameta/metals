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

sealed trait InlayHintOrigin
object InlayHintOrigin {
  case object NamedParameters extends InlayHintOrigin
  case object ByNameParameters extends InlayHintOrigin
  case object ImplicitConversion extends InlayHintOrigin
  case object ImplicitParameters extends InlayHintOrigin
  case object TypeParameters extends InlayHintOrigin
  case object InferredType extends InlayHintOrigin
  case object ClosingLabel extends InlayHintOrigin
  case object XRayMode extends InlayHintOrigin
}

case class InlayHintWithOrigin(
    hint: InlayHint,
    origin: InlayHintOrigin
)

case class InlayHints(
    uri: URI,
    inlayHints: List[InlayHintWithOrigin],
    blockInlayHints: Map[Int, InlayHintBlock]
) {

  def add(
      inlayHint: InlayHint,
      origin: InlayHintOrigin
  ): InlayHints = copy(inlayHints = addInlayHint(inlayHint, origin))

  def add(
      pos: l.Range,
      labelParts: List[LabelPart],
      kind: InlayHintKind,
      origin: InlayHintOrigin
  ): InlayHints =
    copy(inlayHints =
      addInlayHint(
        InlayHints.makeInlayHint(pos.getStart(), labelParts, kind, uri),
        origin
      )
    )

  /*
   * Collects inlay hints in a single expression together and aligns their type labels.
   * Note: Designed for use somewhat specifically for Xray Mode,
   * so it includes some filtering logic specific to that use case.
   */
  def addToBlock(
      pos: l.Range,
      labelParts: List[LabelPart],
      kind: InlayHintKind
  ): InlayHints = {
    // The start pos of each element in a single expression will be the beginning of the expression.
    // We can use this to associate related hints in a map
    val expressionStart = pos.getStart.getLine

    blockInlayHints
      .get(expressionStart)
      .fold(
        copy(blockInlayHints =
          blockInlayHints + (
            pos.getStart.getLine ->
              InlayHintBlock(
                indentLevel = pos.getEnd.getCharacter,
                List(
                  BlockInlayHint(
                    pos,
                    labelParts,
                    kind
                  )
                )
              )
          )
        )
      ) { (ihb: InlayHintBlock) =>
        val newLevel = math.max(pos.getEnd.getCharacter, ihb.indentLevel)

        val newBlock =
          InlayHintBlock(
            indentLevel = newLevel,
            ihb.hints :+ BlockInlayHint(pos, labelParts, kind)
          )

        copy(blockInlayHints = blockInlayHints + (expressionStart -> newBlock))
      }
  }

  private def makeInlayHint(
      bih: BlockInlayHint
  ): InlayHint =
    InlayHints.makeInlayHint(bih.pos.getEnd, bih.labels, bih.kind, uri)

  /**
   * InferType can sometimes generate duplicate hints (e.g. for symbols inside of `for`-comprehensons)
   * That is why we have to deduplicate them when adding new inlay hints
   */
  private def addInlayHint(
      inlayHint: InlayHint,
      origin: InlayHintOrigin
  ): List[InlayHintWithOrigin] = {
    val wrapped = InlayHintWithOrigin(inlayHint, origin)
    // Only filter duplicates for InferredType hints
    if (
      origin == InlayHintOrigin.InferredType && inlayHints.exists(
        _.hint == inlayHint
      )
    ) {
      inlayHints
    } else {
      inlayHints :+ wrapped
    }
  }

  def result(): List[InlayHint] =
    inlayHints.reverse.map(_.hint) ++ blockInlayHints.values.toList
      .flatMap(_.build)
      .map(makeInlayHint)

}

object InlayHints {
  private val gson = new Gson()

  def makeInlayHint(
      pos: l.Range,
      labelParts: List[LabelPart],
      kind: InlayHintKind,
      uri: URI
  ): InlayHint = {
    makeInlayHint(pos.getStart(), labelParts, kind, uri)
  }

  def makeInlayHint(
      pos: l.Position,
      labelParts: List[LabelPart],
      kind: InlayHintKind,
      uri: URI
  ): InlayHint = {
    val hint = new InlayHint()
    hint.setPosition(pos)
    val (label, dataInfo) = labelParts.map(lp => (lp.label, lp.data)).unzip
    hint.setLabel(label.asJava)
    hint.setData(InlayHints.toData(uri.toString(), dataInfo))
    hint.setKind(kind)
    hint
  }
  def empty(uri: URI): InlayHints =
    InlayHints(uri, Nil, Map.empty[Int, InlayHintBlock])

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

  def toData(uri: String, data: List[Either[String, l.Position]]): JsonElement =
    gson.toJsonTree(
      InlineHintData(
        uri,
        data.map {
          case Left(str) => LabelPartData("string", str, null)
          case Right(pos) => LabelPartData("position", null, pos)
        }.toArray
      )
    )

  def fromData(
      json: JsonElement
  ): (String, List[Either[String, l.Position]]) = {
    val data = gson.fromJson(json, classOf[InlineHintData])
    (
      data.uri,
      data.labelParts.toList.map { part =>
        part.dataType match {
          case "position" => Right(part.position)
          case "string" => Left(part.string)
        }
      }
    )
  }
}

final case class InlayHintBlock(
    indentLevel: Int,
    hints: List[BlockInlayHint]
) {
  def build: List[BlockInlayHint] = {
    if (hints.length == 1) Nil
    else
      hints.map { hint =>
        val naiveIndent = indentLevel - hint.pos.getEnd.getCharacter
        val labels =
          if (naiveIndent <= 0) hint.labels
          else LabelPart(" " * naiveIndent) :: hint.labels
        hint.copy(labels = labels)
      }
  }
}

final case class BlockInlayHint(
    pos: l.Range,
    labels: List[LabelPart],
    kind: InlayHintKind
)

final case class InlineHintData(
    uri: String,
    labelParts: Array[LabelPartData]
)

// "string" or "position"
final case class LabelPartData(
    dataType: String,
    string: String,
    position: l.Position
)
