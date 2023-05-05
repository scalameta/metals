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
  ) extends CompletionPosition {

    override def compare(o1: Member, o2: Member): Int =
      (o1, o2) match {
        case (c1: TextEditMember, c2: TextEditMember) =>
          val (comp1, comp2) = (c1.edit.getNewText(), c2.edit.getNewText())
          // For version completions, we want to show the latest version first
          if (comp1.headOption.exists(_.isDigit)) -comp1.compareTo(comp2)
          else super.compare(o1, o2)
        case _ => super.compare(o1, o2)
      }

    override def contribute: List[Member] = {
      val completions =
        coursierComplete.complete(dependency)
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
