package scala.meta.internal.mtags

import scala.annotation.tailrec

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.JavaTokenizer.Token.Word
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.tokenizers.Chars._
import scala.meta.internal.tokenizers.Reporter

class JavaToplevelMtags(val input: Input.VirtualFile) extends MtagsIndexer {

  import JavaTokenizer._

  val reporter: Reporter = Reporter(input)
  val reader: CharArrayReader =
    new CharArrayReader(input, dialects.Scala213, reporter)
  val tokenizer = new JavaTokenizer(reader, input)

  override def language: Language = Language.JAVA

  override def indexRoot(): Unit = {
    if (!input.path.endsWith("module-info.java")) {
      reader.nextRawChar()
      loop
    }
  }

  private def loop: Unit = {
    val token = tokenizer.fetchToken
    token match {
      case Token.EOF =>
      case Token.Package =>
        val paths = readPaths
        paths.foreach { path => pkg(path.value, path.pos) }
        loop
      case Token.Class | Token.Interface | _: Token.Enum | _: Token.Record =>
        tokenizer.fetchToken match {
          case Token.Word(v, pos) =>
            val kind = token match {
              case Token.Interface => SymbolInformation.Kind.INTERFACE
              case _ => SymbolInformation.Kind.CLASS
            }
            withOwner(currentOwner)(tpe(v, pos, kind, 0))
            skipBody
            loop
          case _ =>
            loop
        }
      case _ =>
        loop
    }
  }

  private def readPaths: List[Token.WithPos] = {
    val builder = List.newBuilder[Token.WithPos]
    @tailrec
    def loop(): List[Token.WithPos] = {
      tokenizer.fetchToken match {
        case t: Token.WithPos =>
          builder += t
          loop()
        case Token.Dot => loop()
        case _ =>
          builder.result()
      }
    }
    loop()
  }

  private def skipBody: Unit = {
    @tailrec
    def skipToFirstBrace: Unit =
      tokenizer.fetchToken match {
        case Token.LBrace | Token.EOF => ()
        case _ =>
          skipToFirstBrace
      }

    @tailrec
    def skipToRbrace(open: Int): Unit = {
      tokenizer.fetchToken match {
        case Token.RBrace if open == 1 => ()
        case Token.RBrace =>
          skipToRbrace(open - 1)
        case Token.LBrace =>
          skipToRbrace(open + 1)
        case Token.EOF => ()
        case _ =>
          skipToRbrace(open)
      }
    }

    skipToFirstBrace
    skipToRbrace(1)
  }

}

class JavaTokenizer(reader: CharArrayReader, input: Input) {
  import JavaTokenizer._

  def moveCursor(offset: Int): Unit = {
    reader.moveCursor(offset)
  }

  def fetchToken: Token = {

    @tailrec
    def quotedLiteral(quote: Char): Token = {
      reader.nextChar()
      reader.ch match {
        case `quote` => Token.Literal
        case '\\' =>
          reader.nextChar()
          quotedLiteral(quote)
        case _ => quotedLiteral(quote)
      }
    }

    @tailrec
    def kwOrIdent(start: Int, builder: StringBuilder): Token = {
      val ch = reader.ch
      if (ch != SU && Character.isJavaIdentifierPart(ch)) {
        reader.nextChar()
        kwOrIdent(start, builder.append(ch.toChar))
      } else if (builder.isEmpty) {
        throw new Exception(
          s"Unexpected symbol at word pos: '$ch'. Line: '$readCurrentLine'"
        )
      } else {

        val pos = Position.Range(input, start, reader.begCharOffset)
        builder.mkString match {
          case "package" => Token.Package
          case "class" => Token.Class
          case "interface" => Token.Interface
          case "record" => Token.Record(pos)
          case "enum" => Token.Enum(pos)
          case ident =>
            Token.Word(ident, pos)
        }

      }
    }

    def parseToken: (Token, Boolean) = {
      val first = reader.ch
      first match {
        case ',' | '<' | '>' | '&' | '|' | '!' | '=' | '+' | '-' | '*' | ':' |
            '?' | '%' | '^' | '~' =>
          (Token.SpecialSym, false)
        case '@' => (Token.At, false)
        case SU => (Token.EOF, false)
        case '.' => (Token.Dot, false)
        case '{' => (Token.LBrace, false)
        case '}' => (Token.RBrace, false)
        case ';' => (Token.Semicolon, false)
        case '(' => (Token.LParen, false)
        case ')' => (Token.RParen, false)
        case '[' => (Token.LBracket, false)
        case ']' => (Token.RBracket, false)
        case '"' => (quotedLiteral('"'), false)
        case '\'' => (quotedLiteral('\''), false)
        case '/' =>
          reader.nextChar()
          val next = reader.ch
          next match {
            case '*' =>
              skipMultilineComment(prevStar = false)
              toNextNonWhiteSpace()
              parseToken
            case '/' =>
              skipLine
              toNextNonWhiteSpace()
              parseToken
            case _ =>
              val token = kwOrIdent(
                reader.begCharOffset,
                new StringBuilder().append(first.toChar).append(next.toChar)
              )
              (token, true)
          }
        case _ =>
          val token = kwOrIdent(reader.endCharOffset, new StringBuilder(first))
          (token, true)
      }
    }

    toNextNonWhiteSpace()
    val (t, didNextChar) = parseToken
    if (!didNextChar) reader.nextChar()
    t
  }

  @tailrec
  private def skipMultilineComment(prevStar: Boolean): Unit = {
    reader.nextChar()
    if (prevStar) {
      if (reader.ch == '/') reader.nextChar()
      else skipMultilineComment(prevStar = reader.ch == '*')
    } else skipMultilineComment(prevStar = reader.ch == '*')
  }

  private def skipLine: Unit =
    while ({ val ch = reader.ch; ch != SU && ch != '\n' }) reader.nextChar()

  @tailrec
  private def toNextNonWhiteSpace(): Unit = {
    if (isWhitespace(reader.ch.toChar)) {
      reader.nextChar()
      toNextNonWhiteSpace()
    }
  }

  private def isWhitespace(ch: Char): Boolean = {
    ch match {
      case ' ' | '\t' | CR | LF | FF => true
      case _ => false
    }
  }

  private def readCurrentLine: String = {
    def loop(builder: StringBuilder): String = {
      val ch = reader.ch.toChar
      if (ch == '\n' || ch == SU)
        builder.mkString
      else {
        val next = builder.append(ch)
        reader.nextChar()
        loop(next)
      }
    }

    val lineOffset = reader.lineStartOffset
    val existing = input.text.substring(lineOffset, reader.endCharOffset)
    loop(new StringBuilder().append(existing))
  }

  def consumeUntilWord(): Option[Word] = {
    val token = fetchToken
    token match {
      case Token.EOF => None
      case Word("private", _) => consumeUntilWord()
      case Word("public", _) => consumeUntilWord()
      case Word("protected", _) => consumeUntilWord()
      case Word("static", _) => consumeUntilWord()
      case Token.Class => consumeUntilWord()
      case Token.Interface => consumeUntilWord()
      case _: Token.Enum => consumeUntilWord()
      case _: Token.Record => consumeUntilWord()
      case Token.At =>
        fetchToken
        consumeUntilWord()
      case word: Word => Some(word)
      case Token.LBracket =>
        consumeUntil(Token.RBrace)
        consumeUntilWord()
      case _ => None
    }
  }

  private def consumeUntil(token: Token): Unit = {
    if (fetchToken != Token.EOF && fetchToken != token) {
      consumeUntil(token)
    }
  }
}

object JavaTokenizer {

  sealed trait Token
  object Token {

    sealed trait WithPos extends Token {
      def pos: Position
      def value: String
    }

    case object BOF extends Token
    case object EOF extends Token
    case object Dot extends Token
    case object Package extends Token
    case object Class extends Token
    case object Interface extends Token
    case class Enum(pos: Position) extends WithPos {
      val value: String = "enum"
    }
    case class Record(pos: Position) extends WithPos {
      val value: String = "record"
    }
    case object RBrace extends Token
    case object LBrace extends Token
    case object RParen extends Token
    case object LParen extends Token
    case object RBracket extends Token
    case object LBracket extends Token
    case object Semicolon extends Token
    // any allowed symbol like `=` , `-` and others
    case object SpecialSym extends Token
    case object At extends Token
    case object Literal extends Token

    case class Word(value: String, pos: Position) extends WithPos {
      override def toString: String =
        s"Word($value)"
    }

  }
}
