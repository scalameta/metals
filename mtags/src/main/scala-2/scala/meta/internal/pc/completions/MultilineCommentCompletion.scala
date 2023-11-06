package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait MultilineCommentCompletions { this: MetalsGlobal =>
  case class MultilineCommentCompletion(
      editRange: l.Range,
      pos: Position,
      text: String
  ) extends CompletionPosition {

    override def contribute: List[TextEditMember] = {
      val newText =
        if (clientSupportsSnippets) s" $$0 */"
        else s" */"
      List(
        new TextEditMember(
          "Multiline Comment",
          new l.TextEdit(
            editRange,
            newText
          ),
          completionsSymbol("Multiline"),
          label = Some("/* */"),
          detail = Some("Multiline Comment")
        )
      )
    }
  }

  protected def isMultilineCommentStart(
      pos: Position,
      text: String
  ): Boolean = {
    pos.isDefined &&
    pos.point >= 2 &&
    text.charAt(pos.point - 2) == '/' &&
    text.charAt(pos.point - 1) == '*'
  }

}
