package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait ScalaCliCompletions {
  this: MetalsGlobal =>
  class ScalaCliExtractor(pos: Position) {
    def unapply(path: List[Tree]): Option[String] =
      path match {
        case Nil =>
          CoursierComplete.isScalaCliDep(
            pos.lineContent.replace(CURSOR, "").take(pos.column - 1)
          )
        case (_: PackageDef) :: Nil if pos.source.file.path.endsWith(".sc") =>
          CoursierComplete.isScalaCliDep(
            pos.lineContent.replace(CURSOR, "").take(pos.column - 1)
          )
        case _ => None
      }
  }

  case class ScalaCliCompletion(
      pos: Position,
      text: String,
      dependency: String
  ) extends CompletionPosition {

    override def contribute: List[Member] = {
      val completions =
        CoursierComplete.complete(dependency)
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
