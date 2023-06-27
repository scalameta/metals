package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

trait ScalaCliCompletions {
  this: MetalsGlobal =>
  class ScalaCliExtractor(pos: Position) {
    def unapply(path: List[Tree]): Option[String] =
      path match {
        case Nil =>
          CoursierComplete.isScalaCliDep(
            pos.lineContent.replace(CURSOR, "").take(pos.column - 1)
          )
        // generated script file will end with .sc.scala
        case (_: Template) :: (_: ModuleDef) :: _
            if pos.source.file.path.endsWith(".sc.scala") =>
          CoursierComplete.isScalaCliDep(
            pos.lineContent
              .stripPrefix("/*<script>*/")
              .replace(CURSOR, "")
              .take(pos.column - 1)
          )
        case _ => None
      }
  }

  case class ScalaCliCompletion(
      coursierComplete: CoursierComplete,
      pos: Position,
      text: String,
      dependency: String
  ) extends DependencyCompletion {

    override def contribute: List[Member] = {
      val completions =
        coursierComplete.complete(dependency)
      val (editStart, editEnd) =
        CoursierComplete.inferEditRange(pos.point, text)
      val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp
      makeMembers(completions, editRange)
    }
  }
}
