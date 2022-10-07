package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait SbtLibCompletions {
  this: MetalsGlobal =>

  object SbtLibExtractor {
    def unapply(path: List[Tree]): Option[(Position, String)] = {
      path match {
        // "group@@" %%
        case (lt @ Literal(group: Constant)) :: Select(_, percent1: Name) :: _
            if Set("%", "%%").contains(percent1.decoded) &&
              group.tag == StringTag =>
          val depString = group.stringValue
          Some((lt.pos, depString))

        // "group" %% "artifact@@"
        case (lt @ Literal(artifact: Constant)) :: Apply(
              Select(Literal(group: Constant), percent1: Name),
              _
            ) :: _
            if Set("%", "%%").contains(percent1.decoded) &&
              group.tag == StringTag && artifact.tag == StringTag =>
          val depString =
            group.stringValue +
              percent1.decoded.replace('%', ':') +
              artifact.stringValue
          Some((lt.pos, depString))

        // "group" %% "artifact" % "version@@"
        case (lt @ Literal(revision: Constant)) :: Apply(
              Select(
                Apply(
                  Select(Literal(group: Constant), percent1: Name),
                  List(Literal(artifact: Constant))
                ),
                percent2: Name
              ),
              _
            ) :: _
            if Set("%", "%%").contains(percent1.decoded) &&
              percent2.decoded == "%" &&
              group.tag == StringTag && artifact.tag == StringTag && revision.tag == StringTag =>
          val depString =
            group.stringValue +
              percent1.decoded.replace('%', ':') +
              artifact.stringValue +
              ":" +
              revision.stringValue
          Some((lt.pos, depString))

        case _ =>
          None
      }

    }
  }

  case class SbtLibCompletion(
      pos: Position,
      dependency: String
  ) extends CompletionPosition {
    override def contribute: List[TextEditMember] = {
      val cursorLen = if (dependency.contains(CURSOR)) CURSOR.length() else 0
      val completions =
        CoursierComplete.complete(
          dependency.replace(CURSOR, ""),
          includeScala = false
        )
      val editRange =
        pos.withStart(pos.start + 1).withEnd(pos.end - 1 - cursorLen).toLsp

      completions
        .map(insertText =>
          new TextEditMember(
            filterText = insertText,
            edit = new l.TextEdit(editRange, insertText),
            sym = completionsSymbol(insertText),
            label = Some(insertText)
          )
        )
    }
  }

}
