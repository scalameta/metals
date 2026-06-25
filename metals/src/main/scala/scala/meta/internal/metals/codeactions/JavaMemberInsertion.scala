package scala.meta.internal.metals.codeactions

import scala.meta.internal.parsing.InsertPoint

/**
 * Renders the text of a generated Java member at a given [[InsertPoint]],
 * taking care of the surrounding blank lines and the member indentation.
 */
object JavaMemberInsertion {

  /**
   * @param memberLines the member source, one entry per line, indented
   *   relative to the member itself (the leading class indentation is added
   *   here based on the surrounding code).
   */
  def render(
      text: String,
      insert: InsertPoint,
      memberLines: Seq[String],
  ): String = renderAll(text, insert, Seq(memberLines))

  /**
   * Renders several members at the insertion point, separated by a blank line.
   *
   * @param members one entry per member, each a list of that member's lines.
   */
  def renderAll(
      text: String,
      insert: InsertPoint,
      members: Seq[Seq[String]],
  ): String = {
    val startOffset = insert.startOffset
    val endOffset = insert.endOffset
    val afterOpeningBrace =
      startOffset > 0 && text.charAt(startOffset - 1) == '{'
    val beforeClosingBrace =
      endOffset >= 0 && endOffset < text.length && text.charAt(endOffset) == '}'
    val endIndent = indentAt(text, endOffset)
    val memberIndent = {
      val startLineIndent = lineIndent(text, startOffset)
      if (beforeClosingBrace || (afterOpeningBrace && insert.isInsertion))
        endIndent + indentUnit(text)
      else if (startLineIndent.nonEmpty) startLineIndent
      else if (endIndent.nonEmpty) endIndent
      else indentUnit(text)
    }
    val startsLine = linePrefix(text, startOffset).isEmpty
    val prefix =
      if (beforeClosingBrace && startsLine) ""
      else if (afterOpeningBrace || beforeClosingBrace) "\n"
      else "\n\n"
    val suffix =
      if (beforeClosingBrace) s"\n$endIndent"
      else if (insert.isInsertion) ""
      else s"\n\n$endIndent"
    val body = members
      .map(memberLines =>
        memberLines.map(line => memberIndent + line).mkString("\n")
      )
      .mkString("\n\n")
    s"$prefix$body$suffix"
  }

  /** The indentation unit (tab or spaces) used in the file. */
  def indentUnit(text: String): String = {
    val firstIndent = text.linesIterator
      .map(_.takeWhile(c => c == ' ' || c == '\t'))
      .find(_.nonEmpty)
    firstIndent match {
      case Some(ws) if ws.startsWith("\t") => "\t"
      case _ => "  "
    }
  }

  private def indentAt(text: String, offset: Int): String = {
    val clamped = offset.min(text.length).max(0)
    val candidate = linePrefix(text, clamped)
    if (candidate.forall(_.isWhitespace)) candidate
    else lineIndent(text, offset)
  }

  def lineIndent(text: String, offset: Int): String = {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    text.substring(lineStart).takeWhile(c => c != '\n' && c.isWhitespace)
  }

  private def linePrefix(text: String, offset: Int): String =
    text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset)
}
