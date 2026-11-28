package scala.meta.internal.metals

import java.util.Locale

import scala.collection.mutable.ListBuffer

sealed trait SQLToken
final case class Keyword(value: String) extends SQLToken
final case class Identifier(value: String) extends SQLToken
final case class Function(value: String) extends SQLToken
final case class Literal(
    value: String,
    isClosed: Boolean,
    isEscaped: Boolean = false,
) extends SQLToken
final case class Number(value: String) extends SQLToken
final case class Operator(value: String) extends SQLToken
final case class Whitespace(value: String) extends SQLToken
final case class Other(value: String) extends SQLToken
final case class Comment(value: String, isClosed: Boolean, isBlock: Boolean)
    extends SQLToken
final case class Parameter(value: String) extends SQLToken
final case class QuotedIdentifier(
    value: String,
    isClosed: Boolean,
    quote: Char,
) extends SQLToken

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
      "::", "$", "@@", "@>", "<@", "@?")

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
      case LiteralExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case QuotedIdentifierExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case ParameterExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case IdentifierOrKeywordOrFunctionExtractor(updatedTokenBuffer, tail) =>
        loop(updatedTokenBuffer, tail)
      case NumberExtractor(updatedTokenBuffer, tail) =>
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
      case Some(Literal(_, false, isEscaped)) =>
        loop(LiteralExtractor.continue(tokenBuffer, text.toList, isEscaped))
      case Some(QuotedIdentifier(_, false, quote)) =>
        loop(
          QuotedIdentifierExtractor.continue(tokenBuffer, text.toList, quote)
        )
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
   * Reads up to and including the closing `quote`, where with `isEscaped` a
   * backslash escapes the next character. Returns the text read, whether the
   * quote closed it, and the rest of the part. If the part ends before the
   * quote, `tokenize` continues the token in the next part.
   */
  private def readQuoted(
      chars: List[Char],
      quote: Char,
      isEscaped: Boolean = false,
  ): (String, Boolean, List[Char]) = {
    val tokenBuilder = new StringBuilder()

    @scala.annotation.tailrec
    def loop(chars: List[Char]): (String, Boolean, List[Char]) =
      chars match {
        case '\\' :: ch :: tail if isEscaped =>
          tokenBuilder.addOne('\\').addOne(ch)
          loop(tail)
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

  /**
   * A string literal, optionally with a prefix: raw, bytes or bit string
   * (`r'a'`, `b'a'`, `rb'a'`), escape (`e'a'`, where a backslash escapes the
   * next character), national character (`n'a'`) or hex (`x'0f'`).
   */
  private object LiteralExtractor {
    private val prefixes = Set("r", "b", "rb", "br", "e", "n", "x")

    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars

      def isPrefix(prefix: String) = prefixes(prefix.toLowerCase(Locale.ROOT))

      chars match {
        case '\'' :: tail =>
          Some(read(tokenBuffer, tail, "'", isEscaped = false))
        case p :: '\'' :: tail if isPrefix(p.toString) =>
          Some(read(tokenBuffer, tail, s"$p'", isEscaped = p.toLower == 'e'))
        case p1 :: p2 :: '\'' :: tail if isPrefix(s"$p1$p2") =>
          Some(read(tokenBuffer, tail, s"$p1$p2'", isEscaped = false))
        case _ => None
      }
    }

    def continue(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        isEscaped: Boolean,
    ): (ListBuffer[SQLToken], List[Char]) =
      read(tokenBuffer, chars, opening = "", isEscaped)

    private def read(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        opening: String,
        isEscaped: Boolean,
    ): (ListBuffer[SQLToken], List[Char]) = {
      val (text, isClosed, rest) = readQuoted(chars, '\'', isEscaped)
      tokenBuffer += Literal(
        opening + text,
        isClosed = isClosed,
        isEscaped = isEscaped,
      )
      (tokenBuffer, rest)
    }
  }

  /**
   * An identifier quoted with backticks or double quotes:
   * `` `project.dataset.table` ``, `"column"`.
   */
  private object QuotedIdentifierExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] =
      tokenBufferAndChars match {
        case (tokenBuffer, (quote @ ('`' | '"')) :: tail) =>
          Some(read(tokenBuffer, tail, quote, opening = quote.toString))
        case _ => None
      }

    def continue(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        quote: Char,
    ): (ListBuffer[SQLToken], List[Char]) =
      read(tokenBuffer, chars, quote, opening = "")

    private def read(
        tokenBuffer: ListBuffer[SQLToken],
        chars: List[Char],
        quote: Char,
        opening: String,
    ): (ListBuffer[SQLToken], List[Char]) = {
      val (text, isClosed, rest) = readQuoted(chars, quote)
      tokenBuffer += QuotedIdentifier(
        opening + text,
        isClosed = isClosed,
        quote = quote,
      )
      (tokenBuffer, rest)
    }
  }

  /**
   * A named query parameter, `@name`, or a system variable, `@@name`. A name
   * followed by `(` is a function after an operator instead, as in
   * PostgreSQL's `tsv @@to_tsquery('a')`.
   */
  private object ParameterExtractor {
    def unapply(
        tokenBufferAndChars: (ListBuffer[SQLToken], List[Char])
    ): Option[(ListBuffer[SQLToken], List[Char])] = {
      val (tokenBuffer, chars) = tokenBufferAndChars
      val (at, tail) = chars.span(_ == '@')
      val (name, rest) = tail.span(ch => ch.isLetterOrDigit || ch == '_')

      val isParameter =
        (at.length == 1 || at.length == 2) &&
          name.headOption.exists(ch => ch.isLetter || ch == '_') &&
          !rest.headOption.contains('(')

      if (isParameter) {
        tokenBuffer += Parameter(at.mkString + name.mkString)
        Some((tokenBuffer, rest))
      } else None
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
