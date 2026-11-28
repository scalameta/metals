package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer

sealed trait SQLToken
final case class Keyword(value: String) extends SQLToken
final case class Identifier(value: String) extends SQLToken
final case class Function(value: String) extends SQLToken
final case class Literal(value: String, isClosed: Boolean) extends SQLToken
final case class Number(value: String) extends SQLToken
final case class Operator(value: String) extends SQLToken
final case class Whitespace(value: String) extends SQLToken
final case class Other(value: String) extends SQLToken
final case class Comment(value: String, isClosed: Boolean, isBlock: Boolean)
    extends SQLToken

object SQLTokenizer {
  private val keywords = Set(
    // Keywords
    "select", "insert", "update", "delete", "from", "where", "group", "by",
    "having", "order", "asc", "desc", "limit", "offset", "join", "inner",
    "left", "right", "full", "outer", "on", "as", "distinct", "union", "all",
    "except", "intersect", "exists", "not", "and", "or", "in", "between",
    "like", "is", "null", "case", "when", "then", "if", "else", "end", "create",
    "table", "primary", "key", "foreign", "references", "unique", "check",
    "default", "drop", "alter", "add", "rename", "truncate", "set", "values",
    "into", "index", "view", "with", "recursive", "materialized", "cascade",
    "restrict", "window", "partition", "over",

    // Types
    "int", "integer", "smallint", "bigint", "float", "real", "double",
    "boolean", "date", "time", "timestamp", "interval", "text", "json", "jsonb",
    "uuid",

    // Boolean values
    "true", "false",
  )

  private val operators =
    Set("=", "<", ">", "<=", ">=", "<>", "!=", "+", "-", "*", "/", "%", "->>",
      "::", "$")

  def tokenize(
      text: String,
      lastToken: Option[SQLToken] = None,
  ): List[SQLToken] = {
    @scala.annotation.tailrec
    def loop(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): List[SQLToken] = tokenBufferAndChars match {
      case WhitespaceExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case IdentifierOrKeywordOrFunctionExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case NumberExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case LiteralExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case CommentExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case OperatorExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case (tokenBuffer, ch :: tail) =>
        tokenBuffer += Other(ch.toString)
        loop(tokenBuffer, tail)
      case (tokenBuffer, Nil) => tokenBuffer.result()
    }

    val tokenBuffer = new ListBuffer[SQLToken]()

    lastToken match {
      case Some(Literal(_, false)) =>
        loop(LiteralExtractor.continue(tokenBuffer, text.toList))
      case Some(Comment(_, false, isBlock)) =>
        loop(CommentExtractor.continue(tokenBuffer, text.toList, isBlock))
      case _ => loop((tokenBuffer, text.toList))
    }
  }

  private object WhitespaceExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars
      val tokenBuilder = new StringBuilder()

      @scala.annotation.tailrec
      def loop(chars: List[Char]): Option[(ListBuffer[SQLToken], List[Char])] =
        chars match {
          case ch :: tail if ch.isWhitespace =>
            tokenBuilder.addOne(ch)
            loop(tail)
          case _ if tokenBuilder.nonEmpty =>
            tokenBuffer += Whitespace(tokenBuilder.result())
            Some((tokenBuffer, chars))
          case _ => None
        }

      loop(chars)
    }
  }

  private object IdentifierOrKeywordOrFunctionExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars
      val tokenBuilder = new StringBuilder()

      @scala.annotation.tailrec
      def loop(chars: List[Char]): Option[(ListBuffer[SQLToken], List[Char])] =
        chars match {
          case ch :: tail
              if tokenBuilder.isEmpty && (ch.isLetter || ch == '_') =>
            tokenBuilder.addOne(ch)
            loop(tail)
          case ch :: tail
              if tokenBuilder.nonEmpty && (ch.isLetter || ch == '_' || ch.isDigit) =>
            tokenBuilder.addOne(ch)
            loop(tail)
          case '(' :: _ if tokenBuilder.nonEmpty =>
            tokenBuffer += Function(tokenBuilder.result())
            Some((tokenBuffer, chars))
          case _ if tokenBuilder.nonEmpty =>
            val token = tokenBuilder.result()
            tokenBuffer += (if (keywords.contains(token.toLowerCase()))
                              Keyword(token)
                            else Identifier(token))
            Some((tokenBuffer, chars))
          case _ => None
        }

      loop(chars)
    }
  }

  private object NumberExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars
      val tokenBuilder = new StringBuilder()

      @scala.annotation.tailrec
      def loop(
          chars: List[Char],
          hasDot: Boolean,
      ): Option[(ListBuffer[SQLToken], List[Char])] =
        chars match {
          case ch :: tail if ch.isDigit =>
            tokenBuilder.addOne(ch)
            loop(tail, false)
          case '.' :: ch :: tail
              if tokenBuilder.nonEmpty && ch.isDigit && !hasDot =>
            tokenBuilder.addOne('.')
            tokenBuilder.addOne(ch)
            loop(tail, true)
          case _ if tokenBuilder.nonEmpty =>
            tokenBuffer += Number(tokenBuilder.result())
            Some((tokenBuffer, chars))
          case _ => None
        }

      loop(chars, false)
    }
  }

  /**
   * Reads up to and including the closing `quote`. Returns the text read,
   * whether the quote closed it, and the rest of the part. If the part ends
   * before the quote, `tokenize` continues the token in the next part.
   */
  private def readQuoted(
      chars: List[Char],
      quote: Char,
  ): (String, Boolean, List[Char]) = {
    val tokenBuilder = new StringBuilder()

    @scala.annotation.tailrec
    def loop(chars: List[Char]): (String, Boolean, List[Char]) =
      chars match {
        case `quote` :: tail =>
          tokenBuilder.addOne(quote)
          (tokenBuilder.result(), true, tail)
        case ch :: tail =>
          tokenBuilder.addOne(ch)
          loop(tail)
        case Nil => (tokenBuilder.result(), false, Nil)
      }

    loop(chars)
  }

  /** A string literal: `'a'`. */
  private object LiteralExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] =
      tokenBufferAndChars match {
        case (tokenBuffer, '\'' :: tail) => Some(read(tokenBuffer, tail, "'"))
        case _ => None
      }

    def continue(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
    ): (ListBuffer[SQLToken], List[Char]) =
      read(tokenBuffer, chars, opening = "")

    private def read(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        opening: String,
    ): (ListBuffer[SQLToken], List[Char]) = {
      val (text, isClosed, rest) = readQuoted(chars, '\'')
      tokenBuffer += Literal(opening + text, isClosed = isClosed)
      (tokenBuffer, rest)
    }
  }

  /**
   * A `-- line comment`, which ends before the next newline, or a
   * `/* block comment */`. If the part ends before the comment closes,
   * `tokenize` continues it in the next part.
   */
  private object CommentExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars

      chars match {
        case '-' :: '-' :: tail =>
          Some(read(tokenBuffer, tail, isBlock = false, opening = "--"))
        case '/' :: '*' :: tail =>
          Some(read(tokenBuffer, tail, isBlock = true, opening = "/*"))
        case _ => None
      }
    }

    def continue(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        isBlock: Boolean,
    ): (ListBuffer[SQLToken], List[Char]) =
      read(tokenBuffer, chars, isBlock, opening = "")

    private def read(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        isBlock: Boolean,
        opening: String,
    ): (ListBuffer[SQLToken], List[Char]) = {
      val tokenBuilder = new StringBuilder(opening)

      @scala.annotation.tailrec
      def loop(chars: List[Char]): (ListBuffer[SQLToken], List[Char]) =
        chars match {
          case '*' :: '/' :: tail if isBlock =>
            tokenBuilder.addAll("*/")
            tokenBuffer += Comment(
              tokenBuilder.result(),
              isClosed = true,
              isBlock = isBlock,
            )
            (tokenBuffer, tail)
          case '\n' :: _ if !isBlock =>
            tokenBuffer += Comment(
              tokenBuilder.result(),
              isClosed = true,
              isBlock = isBlock,
            )
            (tokenBuffer, chars)
          case ch :: tail =>
            tokenBuilder.addOne(ch)
            loop(tail)
          case Nil =>
            tokenBuffer += Comment(
              tokenBuilder.result(),
              isClosed = false,
              isBlock = isBlock,
            )
            (tokenBuffer, Nil)
        }

      loop(chars)
    }
  }

  private object OperatorExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars

      chars match {
        case ch1 :: ch2 :: ch3 :: tail if operators(s"$ch1$ch2$ch3") =>
          tokenBuffer += Operator(s"$ch1$ch2$ch3")
          Some((tokenBuffer, tail))
        case ch1 :: ch2 :: tail if operators(s"$ch1$ch2") =>
          tokenBuffer += Operator(s"$ch1$ch2")
          Some((tokenBuffer, tail))
        case ch :: tail if operators(ch.toString) =>
          tokenBuffer += Operator(ch.toString)
          Some((tokenBuffer, tail))
        case _ => None
      }
    }
  }
}
