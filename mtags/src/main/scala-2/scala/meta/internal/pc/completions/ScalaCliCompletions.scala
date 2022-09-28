package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait CliCompletions {
  this: MetalsGlobal =>
  class CliExtractor(pos: Position) {
    def unapply(path: List[Tree]): Option[String] =
      path match {
        case Nil =>
          CoursierComplete.isScalaCliDep(pos.lineContent.stripSuffix(CURSOR))
        case _ => None
      }
  }

  case class ScalaCliCompletions(
      pos: Position,
      text: String,
      dependency: String
  ) extends CompletionPosition {

    override def contribute: List[Member] = {
      val completions =
        CoursierComplete.complete(dependency.stripSuffix(CURSOR))
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
