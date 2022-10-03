package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd.ImportSelector
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SourcePosition

object AmmoniteIvyCompletions:
  def contribute(
      select: Tree,
      selector: List[ImportSelector],
      pos: SourcePosition,
  )(using Context): List[CompletionValue] =

    val query = selector.collectFirst {
      case sel: ImportSelector if sel.sourcePos.encloses(pos) =>
        sel.name.decoded
    }
    query match
      case None => Nil
      case Some(dependency) =>
        val completions = CoursierComplete.complete(dependency)
        completions
          .map(insertText =>
            CompletionValue.AmmoniteIvyImport(
              insertText.stripPrefix(":"),
              Some(insertText),
            )
          )
  end contribute
end AmmoniteIvyCompletions
