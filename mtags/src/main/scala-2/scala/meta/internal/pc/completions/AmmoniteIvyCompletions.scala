package scala.meta.internal.pc.completions

import scala.collection.JavaConverters._

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait AmmoniteIvyCompletions {
  this: MetalsGlobal =>

  case class AmmoniteIvyCompletion(
      select: Tree,
      selector: List[ImportSelector],
      pos: Position,
      editRange: l.Range,
      text: String
  ) extends CompletionPosition {

    override def contribute: List[Member] = {
      val query = selector.collectFirst {
        case sel: ImportSelector if sel.name.toString().contains(CURSOR) =>
          sel.name.decode.replace(CURSOR, "")
      }
      query match {
        case Some(imp) =>
          val completions = CoursierComplete.complete(imp)
          val isInitialCompletion =
            pos.lineContent.trim == s"import $$ivy.$CURSOR"
          val ivyEditRange =
            if (isInitialCompletion) editRange
            else {
              // We need the text edit to span the whole group/artefact/version
              val (rangeStart, _) =
                CoursierComplete.inferEditRange(pos.point, text)
              pos.withStart(rangeStart).withEnd(pos.point).toLsp
            }
          completions.zipWithIndex.map { case (c, index) =>
            new TextEditMember(
              filterText = c,
              edit = new l.TextEdit(
                ivyEditRange,
                if (isInitialCompletion) s"`$c$$0`" else c
              ),
              sym = select.symbol
                .newErrorSymbol(TermName(s"artefact$index"))
                .setInfo(NoType),
              label = Some(c.stripPrefix(":"))
            )
          }
        case _ => List.empty
      }
    }
  }
}
