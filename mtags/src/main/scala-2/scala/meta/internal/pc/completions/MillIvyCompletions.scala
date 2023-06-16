package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.MetalsGlobal

trait MillIvyCompletions {
  this: MetalsGlobal =>
  object MillIvyExtractor {
    def unapply(path: List[Tree]): Option[String] = {
      path match {
        case (lt @ Literal(Constant(dependency: String))) ::
            Apply(
              Select(
                Apply(Ident(TermName("StringContext")), _),
                TermName("ivy")
              ),
              _
            ) :: _ if lt.pos.source.path.isMill =>
          Some(dependency)
        case _ => None
      }
    }
  }

  case class MillIvyCompletion(
      coursierComplete: CoursierComplete,
      pos: Position,
      text: String,
      dependency: String
  ) extends DependencyCompletion {
    override def contribute: List[Member] = {
      val completions =
        coursierComplete.complete(dependency.replace(CURSOR, ""))
      val (editStart, editEnd) =
        CoursierComplete.inferEditRange(pos.point, text)
      val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp

      makeMembers(completions, editRange)

    }
  }
}
