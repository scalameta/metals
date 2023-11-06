package scala.meta.internal.mtags

import scala.meta.Dialect
import scala.meta.inputs._
import scala.meta.internal.tokenizers.Chars._
import scala.meta.internal.tokenizers.Reporter

private[mtags] case class CharArrayReader private (
    buf: Array[Char],
    dialect: Dialect,
    reporter: Reporter,
    /** the last read character */
    var ch: Int = SU,
    /** The offset one past the last read character */
    var begCharOffset: Int = -1, // included
    var endCharOffset: Int = 0, // excluded
    /** The start offset of the current line */
    var lineStartOffset: Int = 0
) {

  def this(input: Input, dialect: Dialect, reporter: Reporter) =
    this(buf = input.chars, dialect = dialect, reporter = reporter)

  import reporter._

  /** Advance one character; reducing CR;LF pairs to just LF */
  final def nextChar(): Unit = {
    nextRawChar()
    if (ch < ' ') {
      skipCR()
      potentialLineEnd()
    }
    if (ch == '"' && !dialect.allowMultilinePrograms) {
      readerError(
        "double quotes are not allowed in single-line quasiquotes",
        at = begCharOffset
      )
    }
  }

  final def nextCommentChar(): Unit = {
    if (endCharOffset >= buf.length) {
      ch = SU
    } else {
      ch = buf(endCharOffset)
      begCharOffset = endCharOffset
      endCharOffset += 1
      checkLineEnd()
    }
  }

  /**
   * Advance one character, leaving CR;LF pairs intact. This is for use in multi-line strings, so
   * there are no "potential line ends" here.
   */
  final def nextRawChar(): Unit = {
    if (endCharOffset >= buf.length) {
      ch = SU
    } else {
      begCharOffset = endCharOffset
      val (hi, hiEnd) = readUnicodeChar(endCharOffset)
      if (!Character.isHighSurrogate(hi)) {
        ch = hi
        endCharOffset = hiEnd
      } else if (hiEnd >= buf.length)
        readerError("invalid unicode surrogate pair", at = begCharOffset)
      else {
        val (lo, loEnd) = readUnicodeChar(hiEnd)
        if (!Character.isLowSurrogate(lo))
          readerError("invalid unicode surrogate pair", at = begCharOffset)
        else {
          ch = Character.toCodePoint(hi, lo)
          endCharOffset = loEnd
        }
      }
    }
  }

  def nextNonWhitespace: Int = {
    while (ch == ' ' || ch == '\t') nextRawChar()
    ch
  }

  /** Read next char interpreting \\uxxxx escapes; doesn't mutate internal state */
  private def readUnicodeChar(offset: Int): (Char, Int) = {
    val c = buf(offset)
    val firstOffset = offset + 1 // offset after a single character

    def evenSlashPrefix: Boolean = {
      var p = firstOffset - 2
      while (p >= 0 && buf(p) == '\\') p -= 1
      (firstOffset - p) % 2 == 0
    }

    if (
      c != '\\' || firstOffset >= buf.length || buf(
        firstOffset
      ) != 'u' || !evenSlashPrefix
    )
      return (c, firstOffset)

    var escapedOffset = firstOffset // offset after an escaped character
    escapedOffset += 1
    while (escapedOffset < buf.length && buf(escapedOffset) == 'u')
      escapedOffset += 1

    // need 4 digits
    if (escapedOffset + 3 >= buf.length)
      return (c, firstOffset)

    def udigit: Int =
      try digit2int(buf(escapedOffset), 16)
      finally escapedOffset += 1

    val code = udigit << 12 | udigit << 8 | udigit << 4 | udigit
    (code.toChar, escapedOffset)
  }

  /** replace CR;LF by LF */
  private def skipCR() =
    if (ch == CR && endCharOffset < buf.length && buf(endCharOffset) == '\\') {
      val (c, nextOffset) = readUnicodeChar(endCharOffset)
      if (c == LF) {
        ch = LF
        endCharOffset = nextOffset
      }
    }

  /** Handle line ends */
  private def potentialLineEnd(): Unit = {
    if (checkLineEnd() && !dialect.allowMultilinePrograms) {
      readerError(
        "line breaks are not allowed in single-line quasiquotes",
        at = begCharOffset
      )
    }
  }

  private def checkLineEnd(): Boolean = {
    val ok = ch == LF || ch == FF
    if (ok) {
      lineStartOffset = endCharOffset
    }
    ok
  }

  /** A new reader that takes off at the current character position */
  def lookaheadReader: CharArrayReader = copy()

  /** A mystery why CharArrayReader.nextChar() returns Unit */
  def getc(): Int = { nextChar(); ch }

  final def wasMultiChar: Boolean = begCharOffset < endCharOffset - 1

  def moveCursor(offset: Int): Unit = {
    begCharOffset = offset - 1
    endCharOffset = offset
    lineStartOffset = input.offsetToLine(offset)
    nextRawChar()
  }

}
