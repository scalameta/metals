package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.meta.tokens.Tokens
import scala.meta.tokens.Token
import scala.meta.XtensionTokenizeInputLike
import scala.meta.XtensionClassifiable

object ScriptFirstImportPosition:

  val usingDirectives: List[String] = List("// using", "//> using")
  val ammHeaders: List[String] = List("// scala", "// ammonite")

  def ammoniteScStartOffset(text: String): Option[Int] =
    val it = tokenize(text).iterator
    startMarkerOffset(it, "/*<start>*/").map { startOffset =>
      val offset =
        skipPrefixesOffset(ammHeaders, it, None)
          .getOrElse(startOffset)

      offset + 1
    }

  def scalaCliScStartOffset(text: String): Option[Int] =
    val iterator = tokenize(text).iterator
    startMarkerOffset(iterator, "/*<script>*/").map { startOffset =>
      val offset =
        skipPrefixesOffset(usingDirectives, iterator, None)
          .getOrElse(startOffset)

      offset + 1
    }

  def skipUsingDirectivesOffset(text: String): Int =
    skipPrefixesOffset(usingDirectives, text)

  def skipPrefixesOffset(prefixes: List[String], text: String): Int =
    val it = tokenize(text).iterator
    if it.hasNext then
      it.next() match
        case _: Token.BOF =>
          skipPrefixesOffset(prefixes, it, None)
            .map(_ + 1)
            .getOrElse(0)
        case _ => 0
    else 0

  @tailrec
  private def startMarkerOffset(
      it: Iterator[Token],
      comment: String,
  ): Option[Int] =
    if it.hasNext then
      it.next() match
        case t: Token.Comment =>
          if t.text == comment then Some(t.pos.end)
          else startMarkerOffset(it, comment)
        case _ => startMarkerOffset(it, comment)
    else None

  @tailrec
  private def skipPrefixesOffset(
      prefixes: List[String],
      it: Iterator[Token],
      lastOffset: Option[Int],
  ): Option[Int] =
    if it.hasNext then
      it.next match
        case t: Token.Comment
            if prefixes.exists(prefix => t.text.startsWith(prefix)) =>
          skipPrefixesOffset(prefixes, it, Some(t.pos.end))
        case t if isWhitespace(t) =>
          skipPrefixesOffset(prefixes, it, lastOffset)
        case _ =>
          lastOffset
    else lastOffset

  private def tokenize(text: String): Tokens =
    val tokenized = text.tokenize.toOption
    tokenized match
      case None => Tokens(Array.empty)
      case Some(v) => v

  private def isWhitespace(t: Token): Boolean =
    t.is[Token.Space] || t.is[Token.Tab] || t.is[Token.CR] ||
      t.is[Token.LF] || t.is[Token.FF] || t.is[Token.LFLF]
end ScriptFirstImportPosition
