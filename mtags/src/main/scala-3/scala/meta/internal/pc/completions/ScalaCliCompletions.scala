package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.util.SourcePosition
import scala.meta.internal.pc.completions.CompletionValue.IvyImport

class ScalaCliCompletions(
    coursierComplete: CoursierComplete,
    pos: SourcePosition,
    text: String,
):
  def unapply(path: List[Tree]): Option[String] =
    def scalaCliDep = CoursierComplete.isScalaCliDep(
      pos.lineContent.take(pos.column).stripPrefix("/*<script>*/")
    )
    path match
      case Nil => scalaCliDep
      // generated script file will end with .sc.scala
      case (_: TypeDef) :: Nil if pos.source.file.path.endsWith(".sc.scala") =>
        scalaCliDep
      case (_: Template) :: (_: TypeDef) :: Nil
          if pos.source.file.path.endsWith(".sc.scala") =>
        scalaCliDep
      case head :: next => None

  def contribute(dependency: String): List[IvyImport] =
    val completions = coursierComplete.complete(dependency)
    val (editStart, editEnd) = CoursierComplete.inferEditRange(pos.point, text)
    val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp
    completions
      .map(insertText =>
        CompletionValue.IvyImport(
          insertText.stripPrefix(":"),
          Some(insertText),
          Some(editRange),
        )
      )
  end contribute

end ScalaCliCompletions
