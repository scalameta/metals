package tests

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.DecorationKind
import scala.meta.pc.SyntheticDecoration

import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

object TestSyntheticDecorations {

  def toEdits(decorations: List[SyntheticDecoration]): List[TextEdit] = {
    val grouped = groupDecorations(decorations)
    makeEdits(grouped)
  }

  private def groupDecorations(
      decorations: List[SyntheticDecoration]
  ): List[List[SyntheticDecoration]] = {
    decorations
      .groupBy(d => (d.range(), d.kind()))
      .toList
      .sortWith { case (((r1, k1), _), ((r2, k2), _)) =>
        if (r1.lt(r2)) true
        else if (r2.lt(r1)) false
        else k1 < k2
      }
      .map(_._2)
  }

  private def makeEdits(
      grouped: List[List[SyntheticDecoration]]
  ): List[TextEdit] = {
    grouped
      .map { case decorations @ (d :: _) =>
        if (d.kind == DecorationKind.InferredType) {
          val decoration = decorations.head
          (decoration.range, decoration.label(), d.kind)
        } else {
          val labels = decorations.map(_.label()).reverse.mkString(", ")
          (d.range, labels, d.kind)
        }
      }
      .flatMap { case (range, label, kind) =>
        val start = range.getStart()
        val end = range.getEnd()
        kind match {
          case DecorationKind.ImplicitParameter =>
            new TextEdit(
              range,
              "(" + label + ")",
            ) :: Nil
          case DecorationKind.ImplicitConversion =>
            List(
              new TextEdit(
                new l.Range(start, start),
                label + "(",
              ),
              new TextEdit(new l.Range(end, end), ")"),
            )
          case DecorationKind.TypeParameter =>
            new TextEdit(
              range,
              "[" + label + "]",
            ) :: Nil
          case _ => Nil
        }
      }
  }
}
