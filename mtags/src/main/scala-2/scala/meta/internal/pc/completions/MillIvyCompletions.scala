package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

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
      pos: Position,
      text: String,
      dependency: String
  ) extends CompletionPosition {
    override def contribute: List[Member] = {
      val completions =
        CoursierComplete.complete(dependency.replace(CURSOR, ""))
      val (editStart, editEnd) =
        CoursierComplete.inferEditRange(pos.point, text)
      val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp

      completions
        .map(insertText =>
          new TextEditMember(
            filterText = insertText,
            edit = new l.TextEdit(editRange, insertText),
            sym = completionsSymbol(insertText),
            label = Some(insertText.stripPrefix(":"))
          )
        )
    }
  }
}
