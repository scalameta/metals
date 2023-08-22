package scala.meta.internal.metals

import scala.annotation.nowarn
import scala.collection.mutable
import scala.{meta => m}

import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.DecorationKind
import scala.meta.internal.pc.LabelPart
import scala.meta.io.AbsolutePath
import scala.meta.pc.InlayHintPart
import scala.meta.pc.RangeParams
import scala.meta.pc.SyntheticDecoration
import scala.meta.tokens.{Token => T}

import org.eclipse.{lsp4j => l}

final class InlayHintsProvider(
    params: RangeParams,
    trees: Trees,
    userConfig: () => UserConfiguration,
    range: Position,
) {

  val path: AbsolutePath = params.uri().toAbsolutePath

  lazy val (withoutTypes, methodPositions) = declarationsWithoutTypes()

  def provide(
      synthteticDecorations: List[SyntheticDecoration]
  ): List[l.InlayHint] = {
    val grouped = groupByRange(synthteticDecorations)
    makeInlayHints(grouped)

  }

  def makeInlayHint(
      pos: l.Position,
      labelParts: List[InlayHintPart],
      kind: l.InlayHintKind,
      addTextEdit: Boolean = false,
  ): l.InlayHint = {
    val hint = new l.InlayHint()
    hint.setPosition(pos)
    val (label, data) =
      labelParts.map { lp =>
        val labelPart = new l.InlayHintLabelPart()
        labelPart.setValue(lp.label())
        (labelPart, lp.symbol())
      }.unzip
    hint.setLabel(label.asJava)
    hint.setData(data.asJava)
    hint.setKind(kind)
    if (addTextEdit) {
      val textEdit = new l.TextEdit()
      textEdit.setRange(new l.Range(pos, pos))
      textEdit.setNewText(labelParts.map(_.label()).mkString)
      hint.setTextEdits(List(textEdit).asJava)
    }
    hint
  }

  private def groupByRange(
      decorations: List[SyntheticDecoration]
  ): List[List[SyntheticDecoration]] =
    decorations
      .groupBy(d => (d.range(), d.kind()))
      .toList
      .sortWith { case (((r1, k1), _), ((r2, k2), _)) =>
        if (r1.lt(r2)) true
        else if (r2.lt(r1)) false
        else k1 < k2
      }
      .map(_._2)

  @nowarn
  private def makeInlayHints(
      grouped: List[List[SyntheticDecoration]]
  ) = {
    val result = mutable.ListBuffer.empty[l.InlayHint]
    grouped
      .map { case decorations @ (d :: _) =>
        if (d.kind == DecorationKind.InferredType) {
          val decoration = decorations.head
          (decoration.range, decoration.labelParts().asScala.toList, d.kind)
        } else {
          val labels0 = decorations.map(_.labelParts().asScala.toList).reverse
          val labels = labels0.head ++ labels0.tail.flatMap { labels =>
            labelPart(", ") :: labels
          }
          (d.range, labels, d.kind)
        }
      }
      .foreach { case (range, labelParts, kind) =>
        kind match {
          case DecorationKind.ImplicitParameter =>
            result += makeInlayHint(
              range.getStart(),
              labelPart("(") :: labelParts ++ List(labelPart(")")),
              l.InlayHintKind.Parameter,
            )
          case DecorationKind.ImplicitConversion =>
            result += makeInlayHint(
              range.getStart(),
              labelParts ++ List(labelPart("(")),
              l.InlayHintKind.Parameter,
            )
            result += makeInlayHint(
              range.getEnd(),
              List(labelPart(")")),
              l.InlayHintKind.Parameter,
            )
          case DecorationKind.TypeParameter =>
            result += makeInlayHint(
              range.getStart(),
              labelPart("[") :: labelParts ++ List(labelPart("]")),
              l.InlayHintKind.Type,
              addTextEdit = true,
            )
          case DecorationKind.InferredType =>
            result += makeInlayHint(
              methodPositions.getOrElse(range, range).getEnd(),
              labelPart(": ") :: labelParts,
              l.InlayHintKind.Type,
              addTextEdit = true,
            )
        }
      }
    result.toList
  }

  private def labelPart(str: String): InlayHintPart = {
    LabelPart(str, "")
  }

  private def declarationsWithoutTypes() = {

    val methodPositions = mutable.Map.empty[l.Range, l.Range]

    def explorePatterns(pats: List[m.Pat]): List[l.Range] = {
      pats.flatMap {
        case m.Pat.Var(nm @ m.Term.Name(_)) =>
          List(nm.pos.toLsp)
        case m.Pat.Extract((_, pats)) =>
          explorePatterns(pats)
        case m.Pat.ExtractInfix(lhs, _, pats) =>
          explorePatterns(lhs :: pats)
        case m.Pat.Tuple(tuplePats) =>
          explorePatterns(tuplePats)
        case m.Pat.Bind(_, rhs) =>
          explorePatterns(List(rhs))
        case _ => Nil
      }
    }

    def visit(tree: m.Tree): List[l.Range] = {
      tree match {
        case enumerator: m.Enumerator.Generator =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case enumerator: m.Enumerator.CaseGenerator =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case enumerator: m.Enumerator.Val =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case param: m.Term.Param =>
          if (param.decltpe.isEmpty) List(param.name.pos.toLsp)
          else Nil
        case cs: m.Case =>
          explorePatterns(List(cs.pat)) ++ visit(cs.body)
        case vl: m.Defn.Val =>
          val values =
            if (vl.decltpe.isEmpty) explorePatterns(vl.pats) else Nil
          values ++ visit(vl.rhs)
        case vr: m.Defn.Var =>
          val values =
            if (vr.decltpe.isEmpty) explorePatterns(vr.pats) else Nil
          values ++ vr.rhs.toList.flatMap(visit)
        case df: m.Defn.Def =>
          val namePos = df.name.pos

          def lastParamPos = for {
            group <- df.paramss.lastOption
            param <- group.lastOption
            token <- param.findFirstTrailing(_.is[T.RightParen])
          } yield token.pos

          def lastTypeParamPos = for {
            typ <- df.tparams.lastOption
            token <- typ.findFirstTrailing(_.is[T.RightBracket])
          } yield token.pos

          def lastParen = if (df.paramss.nonEmpty)
            df.name
              .findFirstTrailing(_.is[T.RightParen])
              .map(_.pos)
          else None

          val values =
            if (df.decltpe.isEmpty) {
              val destination =
                lastParamPos
                  .orElse(lastParen)
                  .orElse(lastTypeParamPos)
                  .getOrElse(namePos)
              methodPositions += namePos.toLsp -> destination.toLsp
              List(namePos.toLsp)
            } else {
              Nil
            }
          values ++ visit(df.body)
        case other =>
          other.children.flatMap(visit)
      }
    }
    if (
      userConfig().showInferredType.contains("true") ||
      userConfig().showInferredType.contains("minimal")
    ) {
      val tree = lastEnclosingTree()
      val declarations: List[l.Range] = tree.flatMap(visit)
      (declarations, methodPositions.toMap)
    } else (Nil, Map.empty[l.Range, l.Range])
  }

  def lastEnclosingTree(): List[m.Tree] = {
    def loop(t: m.Tree): m.Tree = {
      t.children.find(_.pos.encloses(range)) match {
        case Some(child) =>
          loop(child)
        case None =>
          t
      }
    }
    trees
      .get(path)
      .map { tree =>
        val enclosingTree = loop(tree)
        if (range.encloses(enclosingTree.pos)) List(enclosingTree)
        else {
          enclosingTree.children.filter(_.pos.overlapsWith(range))
        }
      }
      .getOrElse(Nil)

  }
}
