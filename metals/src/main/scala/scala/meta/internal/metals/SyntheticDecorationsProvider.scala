package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.VirtualFileParams
import scala.meta.internal.parsing.Trees
import scala.meta.internal.metals.MetalsEnrichments._
import scala.collection.mutable
import scala.{meta => m}
import scala.meta.tokens.{Token => T}
import scala.meta.inputs.Position
import scala.meta.internal.pc.DecorationKind

class SyntheticDecorationsProvider(
    params: VirtualFileParams,
    trees: Trees,
    userConfig: () => UserConfiguration,
) {
  val path = params.uri().toAbsolutePath

  def provide(
      synthteticDecorations: List[SyntheticDecoration]
  ): List[l.InlayHint] = {
    val (withoutTypes, methodPositions) = declarationsWithoutTypes()
    val declarations = withoutTypes.sortWith((a, b) =>
      if (a.start == b.start) a.end < b.end else a.start < b.start
    )

    val (inferredTypeDecorations, otherDecorations) =
      synthteticDecorations.partition(_.kind == 1)

    val result: mutable.ListBuffer[l.InlayHint] = mutable.ListBuffer.empty

    var decorationsIterator = inferredTypeDecorations
    for (pos <- declarations) {
      val (decoration, remainingDecorations) =
        findDecoration(pos, decorationsIterator)
      decorationsIterator = remainingDecorations
      decoration match {
        case None =>
        case Some(decoration) =>
          val hintPos = methodPositions.getOrElse(pos, pos)
          val inlayHint = makeInlayHint(decoration, hintPos)
          result += inlayHint
      }
    }

    val missingTypeDecorations = result.toList
    missingTypeDecorations ++ makeSynthethicDecorations(otherDecorations)

  }

  def makeInlayHint(
      decoration: SyntheticDecoration,
      pos: Position,
  ): l.InlayHint = {
    val hint = new l.InlayHint()
    hint.setPosition(new l.Position(pos.endLine, pos.endColumn))
    hint.setLabel(": " + decoration.text)
    hint.setKind(l.InlayHintKind.forValue(decoration.kind))

    hint
  }

  def makeInlayHint(
      pos: l.Position,
      text: String,
      kind: l.InlayHintKind,
  ) = {
    val hint = new l.InlayHint()
    hint.setPosition(pos)
    hint.setLabel(text)
    hint.setKind(kind)
    hint
  }

  def findDecoration(
      pos: Position,
      decorations: List[SyntheticDecoration],
  ): (Option[SyntheticDecoration], List[SyntheticDecoration]) = {
    val lspPos = pos.toLsp
    def isTarget(dec: SyntheticDecoration): Boolean =
      dec.range().sameAs(lspPos)

    val candidates = decorations.dropWhile(_.range().lt(lspPos))
    val decoration = candidates
      .takeWhile(_.range.getStart.sameAs(lspPos.getStart()))
      .filter(isTarget)
      .headOption

    (decoration, candidates)

  }

  private def makeSynthethicDecorations(
      decorations: List[SyntheticDecoration]
  ) = {
    val result = mutable.ListBuffer.empty[l.InlayHint]
    decorations.foreach { decoration =>
      decoration.kind() match {
        case DecorationKind.ImplicitParameter
            if userConfig().showImplicitArguments =>
          result += makeInlayHint(
            decoration.range().getStart(),
            "(" + decoration.text() + ")",
            l.InlayHintKind.Parameter,
          )
        case DecorationKind.ImplicitConversion
            if userConfig().showImplicitConversionsAndClasses =>
          result += makeInlayHint(
            decoration.range().getStart(),
            decoration.text() + "(",
            l.InlayHintKind.Parameter,
          )
          result += makeInlayHint(
            decoration.range().getEnd(),
            ")",
            l.InlayHintKind.Parameter,
          )

        case DecorationKind.TypeParameter
            if userConfig().showInferredType.contains("true") =>
          result += makeInlayHint(
            decoration.range().getStart(),
            "[" + decoration.text() + "]",
            l.InlayHintKind.Type,
          )
        case _ =>
      }
    }
    result.toList
  }

  private def declarationsWithoutTypes() = {

    val methodPositions = mutable.Map.empty[Position, Position]

    def explorePatterns(pats: List[m.Pat]): List[Position] = {
      pats.flatMap {
        case m.Pat.Var(nm @ m.Term.Name(_)) =>
          List(nm.pos)
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

    def visit(tree: m.Tree): List[Position] = {
      tree match {
        case enumerator: m.Enumerator.Generator =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case enumerator: m.Enumerator.CaseGenerator =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case enumerator: m.Enumerator.Val =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case param: m.Term.Param =>
          if (param.decltpe.isEmpty) List(param.name.pos)
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
              methodPositions += namePos -> destination
              List(namePos)
            } else {
              Nil
            }
          values ++ visit(df.body)
        case other =>
          other.children.flatMap(visit)
      }
    }
    val tree = trees.get(path)
    val declarations: List[Position] = tree.map(visit).getOrElse(Nil)
    (declarations, methodPositions.toMap)
  }

}
