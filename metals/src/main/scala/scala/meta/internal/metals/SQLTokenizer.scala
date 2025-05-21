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
    "select", "as", "or", "and", "group", "order", "by", "where", "limit",
    "join", "asc", "desc", "from", "on", "not", "having", "distinct", "case",
    "when", "then", "else", "end", "for", "exists", "between", "like", "in",
    "is", "null", "create", "table", "insert", "into", "values", "update",
    "set", "delete", "drop", "alter", "add", "rename", "truncate", "union",
    "all", "intersect", "except", "primary", "key", "foreign", "references",
    "check", "unique", "default", "if", "cascade", "restrict", "index", "view",
    "materialized", "procedure", "function", "trigger", "declare", "begin",
    "end", "with", "recursive", "window", "partition", "over", "row_number",
    "rank", "dense_rank", "percent_rank", "ntile", "lag", "lead", "first_value",
    "last_value", "current_date", "current_time", "current_timestamp",
    "localtime", "localtimestamp", "cast", "convert", "coalesce", "nullif",
    "extract", "substring", "position", "overlay", "trim", "both", "leading",
    "trailing", "upper", "lower", "replace", "translate",

    // Types
    "int", "integer", "smallint", "bigint", "float", "real", "double",
    "boolean", "date", "time", "timestamp", "interval", "text", "json", "jsonb",
    "uuid",

    // Boolean values
    "true", "false",
  )

  private val operators =
    Set("=", "<", ">", "<=", ">=", "<>", "!=", "+", "-", "*", "/", "%")

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
          case ch :: tail if ch.isLetter || ch == '_' =>
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
        case ch1 :: ch2 :: tail if operators(ch1.toString + ch2.toString) =>
          tokenBuffer += Operator(ch1.toString + ch2.toString)
          Some((tokenBuffer, tail))
        case ch :: tail if operators(ch.toString) =>
          tokenBuffer += Operator(ch.toString)
          Some((tokenBuffer, tail))
        case _ => None
      }
    }
  }
}
