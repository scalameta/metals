package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait MillIvyCompletions {
  this: MetalsGlobal =>
  object MillIvyExtractor {
    def unapply(path: List[Tree]) = {
      path match {
        case (lt @ Literal(dependency: Constant)) ::
            Apply(Select(Apply(Ident(interpolate), _), ivy), _) :: _
            if (lt.pos.source.path.isMill &&
              dependency.tag == StringTag &&
              interpolate.decoded == "StringContext" &&
              ivy.decoded == "ivy") =>
          Some(dependency.stringValue)
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
