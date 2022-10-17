package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait SbtLibCompletions {
  this: MetalsGlobal =>

  object SbtLibExtractor {
    lazy val isPercents: Set[String] = Set("%", "%%")
    def unapply(path: List[Tree]): Option[(Position, String)] = {
      path match {
        // "group@@" %%
        case (lt @ Literal(Constant(group: String))) :: Select(
              _,
              percent1: Name
            ) :: _ if isPercents(percent1.decoded) =>
          Some((lt.pos, group))

        // "group" %% "artifact@@"
        case (lt @ Literal(Constant(artifact: String))) :: Apply(
              Select(Literal(Constant(group: String)), percent1: Name),
              _
            ) :: _ if isPercents(percent1.decoded) =>
          val depString =
            group +
              percent1.decoded.replace('%', ':') +
              artifact
          Some((lt.pos, depString))

        // "group" %% "artifact" % "version@@"
        case (lt @ Literal(Constant(revision: String))) :: Apply(
              Select(
                Apply(
                  Select(Literal(Constant(group: String)), percent1: Name),
                  List(Literal(Constant(artifact: String)))
                ),
                percent2: Name
              ),
              _
            ) :: _
            if isPercents(percent1.decoded) &&
              percent2.decoded == "%" =>
          val depString =
            group +
              percent1.decoded.replace('%', ':') +
              artifact +
              ":" +
              revision
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
