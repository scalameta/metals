package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.util.matching.Regex

import scala.meta._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.tokens.Token

/**
 * For `.sc` Ammonite and Scala-Cli wraps the code for such files.
 * The following code:
 * ```scala
 * val a = 1
 * ```
 * Is trasnformed and passed into PC as:
 * ```scala
 * ${tool-defauls-imports}
 * object ${wrapperObject} {
 * /*<${scriptMarker}>*/
 *   val a = 1  <-- actual code
 * }
 * ```
 * To find the proper position we need to find the object that contains `/*<${scriptMarker}>*/`
 */
object ScriptFirstImportPosition {

  val shebang = "#!"

  private def adjustShebang(text: String): String =
    text.replaceFirst(shebang, s"//$shebang")

  def ammoniteScStartOffset(text: String): Option[Int] =
    scriptStartOffset(text, "/*<start>*/")

  def scalaCliScStartOffset(text: String): Option[Int] =
    scriptStartOffset(text, "/*<script>*/")

  private def scriptStartOffset(text: String, marker: String) = {
    val iterator = tokenize(adjustShebang(text)).iterator
    startMarkerOffset(iterator, t => t.is[Token.Comment] && t.text == marker)
      .map { startOffset =>
        skipComments(iterator, startOffset)
      }
  }

  def infer(
      text: String,
      isScala3Worksheet: Boolean = false
  ): Int = {
    val iterator = tokenize(adjustShebang(text)).iterator
    val startOffset =
      if (isScala3Worksheet)
        startMarkerOffset(iterator, _.is[Token.LeftBrace]).getOrElse(-1)
      else -1
    skipComments(iterator, startOffset)
  }

  @tailrec
  private def startMarkerOffset(
      it: Iterator[Token],
      isStart: Token => Boolean
  ): Option[Int] = {
    if (it.hasNext) {
      it.next() match {
        case t if isStart(t) => Some(t.pos.end)
        case _ => startMarkerOffset(it, isStart)
      }
    } else None
  }

  private def tokenize(text: String): Tokens = {
    val tokenized = text.safeTokenize.toOption
    tokenized match {
      case None => Tokens(Array.empty)
      case Some(v) => v
    }
  }

  private def skipComments(it: Iterator[Token], startOffset: Int): Int =
    skipComments(it, startOffset, startOffset, 0, false) + 1

  @tailrec
  private def skipComments(
      it: Iterator[Token],
      beforeComment: Int,
      lastOffset: Int,
      newLines: Int,
      foundShebang: Boolean,
      foundCommentThatIsNotUsingDirective: Boolean = false
  ): Int = {
    if (it.hasNext) {
      val n = it.next()
      n match {
        case t: Token.Comment if t.value.startsWith(shebang) =>
          skipComments(
            it,
            beforeComment,
            t.pos.end,
            newLines = 0,
            foundShebang = true,
            foundCommentThatIsNotUsingDirective
          )
        case t: Token.Comment if newLines > 1 =>
          skipComments(
            it,
            lastOffset,
            t.pos.end,
            newLines = 0,
            foundShebang,
            foundCommentThatIsNotUsingDirective =
              foundCommentThatIsNotUsingDirective || !isScalaCliUsingDirectiveComment(
                t
              )
          )
        case t: Token.Comment =>
          skipComments(
            it,
            beforeComment,
            t.pos.end,
            newLines = 0,
            foundShebang,
            foundCommentThatIsNotUsingDirective =
              foundCommentThatIsNotUsingDirective || !isScalaCliUsingDirectiveComment(
                t
              )
          )
        case t: Token.AtEOL =>
          skipComments(
            it,
            beforeComment,
            lastOffset,
            newLines + t.newlines,
            foundShebang,
            foundCommentThatIsNotUsingDirective
          )
        case _: Token.Whitespace =>
          skipComments(
            it,
            beforeComment,
            lastOffset,
            newLines,
            foundShebang,
            foundCommentThatIsNotUsingDirective
          )
        case _: Token.BOF =>
          skipComments(
            it,
            beforeComment,
            lastOffset,
            newLines,
            foundShebang,
            foundCommentThatIsNotUsingDirective
          )
        case _ =>
          // There is an empty line between the comment and the code, so its not a doc
          val maybeOffset =
            if (newLines > 1 || !foundCommentThatIsNotUsingDirective) lastOffset
            else beforeComment
          if (foundShebang) maybeOffset - 2
          else maybeOffset
      }
    } else lastOffset
  }

  val scalaCliUsingDirectiveRegex: Regex = """//>\s*using.*"""".r

  private def isScalaCliUsingDirectiveComment(t: Token.Comment): Boolean =
    t.text match {
      case scalaCliUsingDirectiveRegex() => true
      case _ => false
    }

}
