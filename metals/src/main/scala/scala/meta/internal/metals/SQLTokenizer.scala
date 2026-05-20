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
        LiteralExtractor
          .extract(tokenBuffer, text.toList, isLiteralContinuation = true)
          .map(loop)
          .getOrElse(Nil)
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

  private object LiteralExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] =
      extract(
        tokenBuffer = tokenBufferAndChars._1,
        chars = tokenBufferAndChars._2,
        isLiteralContinuation = false,
      )

    def extract(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        isLiteralContinuation: Boolean,
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val tokenBuilder = new StringBuilder()

      @scala.annotation.tailrec
      def loop(chars: List[Char]): Option[(ListBuffer[SQLToken], List[Char])] =
        chars match {
          case '\'' :: tail if tokenBuilder.isEmpty && !isLiteralContinuation =>
            tokenBuilder.addOne('\'')
            loop(tail)
          case '\'' :: tail if tokenBuilder.nonEmpty || isLiteralContinuation =>
            tokenBuffer += Literal(tokenBuilder.result() + "'", isClosed = true)
            Some((tokenBuffer, tail))
          case ch :: tail if tokenBuilder.nonEmpty || isLiteralContinuation =>
            tokenBuilder.addOne(ch)
            loop(tail)
          case _ if tokenBuilder.nonEmpty =>
            tokenBuffer += Literal(tokenBuilder.result(), isClosed = false)
            Some((tokenBuffer, chars))
          case _ => None
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
