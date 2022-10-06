package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.util.SourcePosition
class ScalaCliCompletions(pos: SourcePosition, text: String):
  def unapply(path: List[Tree]) =
    path match
      case head :: next => None
      case Nil =>
        CoursierComplete.isScalaCliDep(pos.lineContent.take(pos.column))

  def contribute(dependency: String) =
    val completions = CoursierComplete.complete(dependency)
    val (editStart, editEnd) = CoursierComplete.inferEditRange(pos.point, text)
    val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp
    completions
      .map(insertText =>
        CompletionValue.ScalaCLiImport(
          insertText.stripPrefix(":"),
          Some(insertText),
          Some(editRange),
        )
      )
  end contribute

end ScalaCliCompletions
