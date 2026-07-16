package scala.meta.internal.metals.codeactions

import scala.meta.internal.parsing.InsertPoint

/**
 * Renders the text of generated Java members at a given [[InsertPoint]],
 * taking care of the surrounding blank lines and the member indentation.
 *
 * The bulk of the logic lives in [[Surroundings]], which inspects the few
 * characters around the insertion point and derives, once, everything the
 * renderer needs: the indentation to apply to each member line and the blank
 * lines to add before and after the generated code.
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
    val around = new Surroundings(text, insert)
    val body = members
      .map(member => member.map(around.memberIndent + _).mkString("\n"))
      .mkString("\n\n")
    s"${around.prefix}$body${around.suffix}"
  }

  /**
   * The indentation unit used in the file: a single tab for tab-indented
   * files, otherwise the leading space width inferred from the first indented
   * line (falling back to two spaces when nothing is indented).
   */
  def indentUnit(text: String): String =
    text.linesIterator
      .map(_.takeWhile(c => c == ' ' || c == '\t'))
      .find(_.nonEmpty) match {
      case Some(ws) if ws.startsWith("\t") => "\t"
      case Some(ws) => ws
      case None => "  "
    }

  /** Leading whitespace of the line containing `offset`. */
  def lineIndent(text: String, offset: Int): String = {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    text.substring(lineStart).takeWhile(c => c != '\n' && c.isWhitespace)
  }

  /** Text between the start of the line containing `offset` and `offset`. */
  def linePrefix(text: String, offset: Int): String =
    text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset)

  /**
   * Indentation at `offset`: the prefix on its line when that prefix is all
   * whitespace (e.g. just before a `}` on its own line), otherwise the
   * leading indentation of the line.
   */
  private def indentAt(text: String, offset: Int): String = {
    val candidate = linePrefix(text, offset.min(text.length).max(0))
    if (candidate.forall(_.isWhitespace)) candidate
    else lineIndent(text, offset)
  }

  /**
   * The handful of facts about the characters around the insertion point that
   * drive how the member is rendered.
   */
  private final class Surroundings(text: String, insert: InsertPoint) {
    private val startOffset = insert.startOffset
    private val endOffset = insert.endOffset

    /** Inserting right after an opening `{`. */
    private val afterOpeningBrace =
      startOffset > 0 && text.charAt(startOffset - 1) == '{'

    /** Inserting right before a closing `}`. */
    private val beforeClosingBrace =
      endOffset >= 0 && endOffset < text.length && text.charAt(endOffset) == '}'

    /** Nothing but the start offset itself begins its line. */
    private val startsLine = linePrefix(text, startOffset).isEmpty

    /** Indentation of the closing brace / end-of-insertion line. */
    private val endIndent = indentAt(text, endOffset)

    /** Indentation prepended to every generated member line. */
    val memberIndent: String = {
      val startLineIndent = lineIndent(text, startOffset)
      // One level deeper than the enclosing braces when we sit between them.
      if (beforeClosingBrace || (afterOpeningBrace && insert.isInsertion))
        endIndent + indentUnit(text)
      // Otherwise align with whichever neighbouring line we can see.
      else if (startLineIndent.nonEmpty) startLineIndent
      else if (endIndent.nonEmpty) endIndent
      else indentUnit(text)
    }

    /** Blank lines emitted before the first member. */
    val prefix: String =
      if (beforeClosingBrace && startsLine) ""
      else if (afterOpeningBrace || beforeClosingBrace) "\n"
      else "\n\n"

    /** Blank lines (and trailing indentation) emitted after the last member. */
    val suffix: String =
      if (beforeClosingBrace) s"\n$endIndent"
      else if (insert.isInsertion) ""
      else s"\n\n$endIndent"
  }
}
