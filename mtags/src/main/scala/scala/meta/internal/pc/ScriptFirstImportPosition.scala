package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta._

/**
 * Used to determine the position for the first import for scala-cli `.scala` and `.sc` files.
 * For scala-cli sources we need to skip `//> using` comments.
 *
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

  val usingDirectives: List[String] = List("// using", "//> using")

  def ammoniteScStartOffset(text: String): Option[Int] = {
    val it = tokenize(text).iterator
    startMarkerOffset(it, "/*<start>*/").map(_ + 1)
  }

  def scalaCliScStartOffset(text: String): Option[Int] = {
    val iterator = tokenize(text).iterator
    startMarkerOffset(iterator, "/*<script>*/").map { startOffset =>
      val offset =
        skipUsingDirectivesOffset(iterator, None)
          .getOrElse(startOffset)

      offset + 1
    }
  }

  def skipUsingDirectivesOffset(text: String): Int = {
    val it = tokenize(text).iterator
    if (it.hasNext) {
      it.next() match {
        case _: Token.BOF =>
          skipUsingDirectivesOffset(it, None)
            .map(_ + 1)
            .getOrElse(0)
        case _ => 0
      }
    } else 0
  }

  @tailrec
  private def startMarkerOffset(
      it: Iterator[Token],
      comment: String
  ): Option[Int] = {
    if (it.hasNext) {
      it.next() match {
        case t: Token.Comment =>
          if (t.text == comment) Some(t.pos.end)
          else startMarkerOffset(it, comment)
        case _ => startMarkerOffset(it, comment)
      }
    } else None
  }

  @tailrec
  private def skipUsingDirectivesOffset(
      it: Iterator[Token],
      lastOffset: Option[Int]
  ): Option[Int] = {
    if (it.hasNext) {
      it.next match {
        case t: Token.Comment
            if usingDirectives.exists(prefix => t.text.startsWith(prefix)) =>
          skipUsingDirectivesOffset(it, Some(t.pos.end))
        case t if isWhitespace(t) =>
          skipUsingDirectivesOffset(it, lastOffset)
        case _ =>
          lastOffset
      }
    } else lastOffset
  }

  private def tokenize(text: String): Tokens = {
    val tokenized = text.tokenize.toOption
    tokenized match {
      case None => Tokens(Array.empty)
      case Some(v) => v
    }
  }

  private def isWhitespace(t: Token): Boolean =
    t.is[Token.Space] || t.is[Token.Tab] || t.is[Token.CR] ||
      t.is[Token.LF] || t.is[Token.FF] || t.is[Token.LFLF]
}
