package scala.meta.internal.mtags

import java.nio.file.Paths
import java.util.Optional

import scala.annotation.tailrec
import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Report
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.tokenizers.CharArrayReader
import scala.meta.pc.reports.ReportContext

class JavaToplevelMtags(
    val input: Input.VirtualFile,
    includeInnerClasses: Boolean
)(implicit rc: ReportContext)
    extends MtagsIndexer {

  import JavaToplevelMtags._

  val reader: CharArrayReader =
    new CharArrayReader(input.value.toCharArray(), dialects.Scala213)

  override def overrides(): List[(String, List[OverriddenSymbol])] =
    overridden.result

  private val overridden = List.newBuilder[(String, List[OverriddenSymbol])]

  private def addOverridden(symbols: List[OverriddenSymbol]) =
    overridden += ((currentOwner, symbols))

  override def language: Language = Language.JAVA

  override def indexRoot(): Unit = {
    try {
      if (!input.path.endsWith("module-info.java")) {
        reader.nextRawChar()
        loop(None)
      }
    } catch {
      case NonFatal(e) =>
        rc.unsanitized()
          .create(() =>
            new Report(
              s"failed-idex-java",
              s"""|Java indexer failed with and exception.
                  |```Java
                  |${input.text}
                  |```
                  |""".stripMargin,
              s"Java indexer failed with and exception.",
              path = Optional.of(Paths.get(input.path).toUri()),
              id = Optional.of(input.path),
              error = Some(e)
            )
          )
    }
  }

  def readPackage: List[String] = {
    fetchToken // start of file
    fetchToken match {
      case Token.Package => readPaths.map(_.value)
      case _ => Nil
    }
  }

  @tailrec
  private def loop(region: Option[Region]): Unit = {
    val token = fetchToken
    token match {
      case Token.EOF =>
      case Token.Package =>
        val paths = readPaths
        paths.foreach { path => pkg(path.value, path.pos) }
        loop(region)
      case Token.Class | Token.Interface | _: Token.Enum | _: Token.Record =>
        fetchToken match {
          case Token.Word(v, pos) =>
            val kind = token match {
              case Token.Interface => SymbolInformation.Kind.INTERFACE
              case _ => SymbolInformation.Kind.CLASS
            }
            val previousOwner = currentOwner
            tpe(v, pos, kind, 0)
            if (includeInnerClasses) {
              collectTypeHierarchyInformation
              loop(Some(Region(region, currentOwner, lBraceCount = 1)))
            } else {
              skipBody
              currentOwner = previousOwner
              loop(region)
            }
          case Token.LBrace =>
            loop(region.map(_.lBrace()))
          case Token.RBrace =>
            val newRegion = region.flatMap(_.rBrace())
            newRegion.foreach(reg => currentOwner = reg.owner)
            loop(newRegion)
          case _ =>
            loop(region)
        }
      case Token.LBrace =>
        loop(region.map(_.lBrace()))
      case Token.RBrace =>
        val newRegion = region.flatMap(_.rBrace())
        newRegion.foreach(reg => currentOwner = reg.owner)
        loop(newRegion)
      case _ =>
        loop(region)
    }
  }

  private def collectTypeHierarchyInformation: Unit = {
    val implementsOrExtends = List.newBuilder[String]
    @tailrec
    def skipUntilOptImplementsOrExtends: Token = {
      fetchToken match {
        case t @ (Token.Implements | Token.Extends) => t
        case Token.EOF => Token.EOF
        case Token.LBrace => Token.LBrace
        case _ => skipUntilOptImplementsOrExtends
      }
    }

    @tailrec
    def collectHierarchy: Unit = {
      fetchToken match {
        case Token.Word(v, _) =>
          // emit here
          implementsOrExtends += v
          collectHierarchy
        case Token.LBrace =>
        case Token.LParen =>
          skipBalanced(Token.LParen, Token.RParen)
          collectHierarchy
        case Token.LessThan =>
          skipBalanced(Token.LessThan, Token.GreaterThan)
          collectHierarchy
        case Token.EOF =>
        case _ => collectHierarchy
      }
    }

    skipUntilOptImplementsOrExtends match {
      case Token.Implements | Token.Extends =>
        collectHierarchy
        addOverridden(
          implementsOrExtends.result.distinct.map(UnresolvedOverriddenSymbol(_))
        )
      case _ =>
    }
  }

  @tailrec
  private def skipMultilineComment(prevStar: Boolean): Unit = {
    reader.nextChar()
    if (prevStar) {
      if (reader.ch == '/') reader.nextChar()
      else skipMultilineComment(prevStar = reader.ch == '*')
    } else skipMultilineComment(prevStar = reader.ch == '*')
  }

  private def fetchToken: Token = {

    @tailrec
    def quotedLiteral(quote: Char): Token = {
      reader.nextChar()

      if (reader.endCharOffset >= reader.buf.length)
        throw new RuntimeException("Broken file, quote doesn't end.")

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
      if (ch != Chars.SU && Character.isJavaIdentifierPart(ch)) {
        reader.nextChar()
        kwOrIdent(start, builder.append(ch.toChar))
      } else if (builder.isEmpty) {
        unexpectedCharacter(ch.toChar, reader.endCharOffset)
      } else {

        val pos = Position.Range(input, start, reader.endCharOffset)
        builder.mkString match {
          case "package" => Token.Package
          case "class" => Token.Class
          case "interface" => Token.Interface
          case "record" => Token.Record(pos)
          case "enum" => Token.Enum(pos)
          case "extends" => Token.Extends
          case "implements" => Token.Implements
          case ident =>
            Token.Word(ident, pos)
        }

      }
    }

    def parseToken: (Token, Boolean) = {
      val first = reader.ch
      first match {
        case ',' | '&' | '|' | '!' | '=' | '+' | '-' | '*' | '@' | ':' | '?' |
            '%' | '^' | '~' =>
          (Token.SpecialSym, false)
        case Chars.SU => (Token.EOF, false)
        case '.' => (Token.Dot, false)
        case '{' => (Token.LBrace, false)
        case '}' => (Token.RBrace, false)
        case ';' => (Token.Semicolon, false)
        case '(' => (Token.LParen, false)
        case ')' => (Token.RParen, false)
        case '[' => (Token.LBracket, false)
        case ']' => (Token.RBracket, false)
        case '<' => (Token.LessThan, false)
        case '>' => (Token.GreaterThan, false)
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
        case '#' =>
          reader.nextChar()
          kwOrIdent(reader.endCharOffset, new StringBuilder(first + 1)) match {
            case Token.Word("include", _) => (Token.IncludeHeader, true)
            case _ => unexpectedCharacter('#', first)
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

  private def readPaths: List[Token.WithPos] = {
    val builder = List.newBuilder[Token.WithPos]
    @tailrec
    def loop(): List[Token.WithPos] = {
      fetchToken match {
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

  private def isWhitespace(ch: Char): Boolean = {
    ch match {
      case ' ' | '\t' | Chars.CR | Chars.LF | Chars.FF => true
      case _ => false
    }
  }

  private def skipBody: Unit = {
    @tailrec
    def skipToFirstBrace: Unit =
      fetchToken match {
        case Token.LBrace | Token.EOF => ()
        case _ =>
          skipToFirstBrace
      }

    skipToFirstBrace
    skipBalanced(Token.LBrace, Token.RBrace)
  }

  @tailrec
  private def skipBalanced(
      openingToken: Token,
      closingToken: Token,
      open: Int = 1
  ): Unit = {
    fetchToken match {
      case t if t == closingToken && open == 1 => ()
      case t if t == closingToken =>
        skipBalanced(openingToken, closingToken, open - 1)
      case t if t == openingToken =>
        skipBalanced(openingToken, closingToken, open + 1)
      case Token.EOF => ()
      case _ =>
        skipBalanced(openingToken, closingToken, open)
    }
  }

  private def skipLine: Unit =
    while ({ val ch = reader.ch; ch != Chars.SU && ch != '\n' })
      reader.nextChar()

  @tailrec
  private def toNextNonWhiteSpace(): Unit = {
    if (isWhitespace(reader.ch.toChar)) {
      reader.nextChar()
      toNextNonWhiteSpace()
    }
  }

  private def readCurrentLine: String = {
    def loop(builder: StringBuilder): String = {
      val ch = reader.ch.toChar
      if (ch == '\n' || ch == Chars.SU)
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

  private def unexpectedCharacter(c: Char, pos: Int): Nothing =
    throw new Exception(
      s"Unexpected symbol '$c' at word pos: '$pos' Line: '$readCurrentLine'"
    )

}

object JavaToplevelMtags {

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
    case object Implements extends Token
    case object Extends extends Token
    case object RBrace extends Token
    case object LBrace extends Token
    case object RParen extends Token
    case object LParen extends Token
    case object RBracket extends Token
    case object LBracket extends Token
    case object LessThan extends Token
    case object GreaterThan extends Token
    case object Semicolon extends Token
    // any allowed symbol like `=` , `-` and others
    case object SpecialSym extends Token
    case object Literal extends Token
    case object IncludeHeader extends Token

    case class Word(value: String, pos: Position) extends WithPos {
      override def toString: String =
        s"Word($value)"
    }

  }

  case class Region(
      previousRegion: Option[Region],
      owner: String,
      lBraceCount: Int
  ) {
    def lBrace(): Region = Region(previousRegion, owner, lBraceCount + 1)
    def rBrace(): Option[Region] =
      if (lBraceCount == 1) previousRegion
      else Some(Region(previousRegion, owner, lBraceCount - 1))
  }
}
