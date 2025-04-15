package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

trait ScalaCliCompletions {
  this: MetalsGlobal =>
  class ScalaCliExtractor(pos: Position) {
    def unapply(path: List[Tree]): Option[String] = {
      def scalaCliDep = CoursierComplete.isScalaCliDep(
        pos.lineContent
          .take(pos.column - 1)
          .stripPrefix("/*<script>*/")
      )

      def default = CoursierComplete.isScalaCliDep(
        pos.lineContent.replace(CURSOR, "").take(pos.column - 1)
      )

      path match {
        case Nil => default
        // worksheets with using directives
        case (pkg: PackageDef) :: Nil if nme.EMPTY_PACKAGE_NAME == pkg.name =>
          default
        // generated script file will end with .sc.scala
        case (_: Template) :: (_: ModuleDef) :: (_: PackageDef) :: Nil
            if pos.source.file.path.endsWith(".sc.scala") =>
          scalaCliDep
        case (_: Template) :: (_: ClassDef) :: (_: PackageDef) :: Nil
            if pos.source.file.path.endsWith(".sc.scala") =>
          scalaCliDep
        case _ => None
      }
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
