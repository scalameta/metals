package scala.meta.internal.pc.completions

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
          val prefix = "import $ivy."
          val afterImport =
            pos.lineContent.stripPrefix(prefix).replace(CURSOR, "")
          val isInitialCompletion = pos.lineContent
            .startsWith(prefix) && !afterImport.headOption
            .exists(c => c == '`' || c == '{')

          val (rangeStart, completeInput) =
            if (isInitialCompletion && !afterImport.contains(':')) {
              val start = // Here we need start to be the point after 'import $ivy.'
                pos.source.lineToOffset(pos.line - 1).max(0) + prefix.length
              (start, afterImport.trim)
            } else {
              // We need the text edit to span the whole group/artefact/version
              val (start, _) = CoursierComplete.inferEditRange(pos.point, text)
              (start, imp)
            }
          val completions = CoursierComplete.complete(completeInput)
          val ivyEditRange = pos.withStart(rangeStart).withEnd(pos.point).toLsp
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
