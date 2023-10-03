package scala.meta.internal.metals

import scala.annotation.nowarn
import scala.collection.mutable
import scala.{meta => m}

import scala.meta.internal.decorations._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.DecorationKind
import scala.meta.io.AbsolutePath
import scala.meta.pc.SyntheticDecoration
import scala.meta.tokens.{Token => T}

import org.eclipse.{lsp4j => l}

final class SyntheticDecorationsProvider(
    params: m.pc.VirtualFileParams,
    trees: Trees,
    userConfig: () => UserConfiguration,
) {

  val path: AbsolutePath = params.uri().toAbsolutePath

  lazy val (declsWithoutTypes, methodPositions) = declarationsWithoutTypes()

  def provide(
      synthteticDecorations: List[SyntheticDecoration]
  ): List[DecorationOptions] = {
    val grouped = groupByRange(synthteticDecorations)
    makeDecorations(grouped)

  }

  def makeDecoration(
      pos: l.Position,
      label: String,
  ): DecorationOptions = {
    val lspRange = new l.Range(pos, pos)
    new DecorationOptions(
      lspRange,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          label,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7,
        )
      ),
    )
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

  /**
   * Puts multiple implicit parameters and type parameters on the same position into one decoration.
   */
  @nowarn
  private def zipDecorations(
      grouped: List[List[SyntheticDecoration]]
  ) = {
    grouped
      .map { case decorations @ (d :: _) =>
        if (
          d.kind == DecorationKind.InferredType ||
          d.kind() == DecorationKind.ImplicitConversion
        ) {
          val decoration = decorations.head
          (decoration.range, decoration.label(), d.kind)
        } else {
          val labels = decorations.map(_.label()).reverse.mkString(", ")
          (d.range, labels, d.kind)
        }
      }
  }

  private def makeDecorations(
      grouped: List[List[SyntheticDecoration]]
  ) = {
    val result = mutable.ListBuffer.empty[DecorationOptions]
    zipDecorations(grouped)
      .foreach { case (range, label, kind) =>
        kind match {
          case DecorationKind.ImplicitParameter =>
            result += makeDecoration(
              range.getStart(),
              "(" + label + ")",
            )
          case DecorationKind.ImplicitConversion =>
            result += makeDecoration(
              range.getStart(),
              label + "(",
            )
            result += makeDecoration(
              range.getEnd(),
              ")",
            )
          case DecorationKind.TypeParameter =>
            result += makeDecoration(
              range.getStart(),
              "[" + label + "]",
            )
          case DecorationKind.InferredType =>
            result += makeDecoration(
              methodPositions.getOrElse(range, range).getEnd(),
              ": " + label,
            )
        }
      }
    result.toList
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
      val tree = trees.get(path).toList
      val declarations: List[l.Range] = tree.flatMap(visit)
      (declarations, methodPositions.toMap)
    } else (Nil, Map.empty[l.Range, l.Range])
  }

}
