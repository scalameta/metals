package scala.meta.internal.mtags

object Chars {
  final val LF = '\u000A'
  final val FF = '\u000C'
  final val CR = '\u000D'
  final val SU = '\u001A'

  /**
   * Convert a character digit to an Int according to given base, -1 if no success
   */
  def digit2int(ch: Int, base: Int): Int = {
    val num =
      (
        if (ch <= '9') ch - '0'
        else if ('a' <= ch && ch <= 'z') ch - 'a' + 10
        else if ('A' <= ch && ch <= 'Z') ch - 'A' + 10
        else -1
      )
    if (0 <= num && num < base) num else -1
  }

  /** Can character form part of an alphanumeric Scala identifier? */
  def isIdentifierPart(c: Int): Boolean =
    (c == '$') || Character.isUnicodeIdentifierPart(c)

}
