package scala.meta.internal.mtags

import java.nio.file.Paths

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.tokenizers.CharArrayReader

class JavaToplevelMtags(
    val input: Input.VirtualFile,
    includeInnerClasses: Boolean
)(implicit rc: ReportContext)
    extends MtagsIndexer {

  import JavaToplevelMtags._

  val reader: CharArrayReader = new CharArrayReader(input, dialects.Scala213)

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
        rc.unsanitized.create(
          new Report(
            s"failed-idex-java",
            s"""|Java indexer failed with and exception.
                |```Java
                |${input.text}
                |```
                |""".stripMargin,
            s"Java indexer failed with and exception.",
            path = Try(Paths.get(input.path).toUri()).toOption,
            id = Some(input.path),
            error = Some(e)
          )
        )
    }
  }

  def readPackage: List[String] = {
    fetchToken() // start of file
    fetchToken() match {
      case Token.Package => readPaths.map(_.value)
      case _ => Nil
    }
  }

  @tailrec
  private def loop(region: Option[Region]): Unit = {
    val token = fetchToken()
    token match {
      case Token.EOF =>
      case Token.Package =>
        val paths = readPaths
        paths.foreach { path => pkg(path.value, path.pos) }
        loop(region)
      case Token.Class | Token.Interface | Token.Enum | Token.Record =>
        fetchToken() match {
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
      fetchToken() match {
        case t @ (Token.Implements | Token.Extends) => t
        case Token.EOF => Token.EOF
        case Token.LBrace => Token.LBrace
        case _ => skipUntilOptImplementsOrExtends
      }
    }

    @tailrec
    def collectHierarchy: Unit = {
      fetchToken() match {
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

  private def isEndOfFile: Boolean = {
    reader.ch == Chars.SU
  }

  @tailrec
  private def skipMultilineComment(prevStar: Boolean): Unit = {
    if (isEndOfFile) {
      // Prevent infinite loop
      return
    }
    reader.nextChar()
    if (prevStar) {
      if (reader.ch == '/') reader.nextChar()
      else skipMultilineComment(prevStar = reader.ch == '*')
    } else {
      skipMultilineComment(prevStar = reader.ch == '*')
    }
  }

  private def fetchToken(inPath: Boolean = false): Token = {

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
        ignoreLine()
      } else {

        val pos = Position.Range(input, start, reader.endCharOffset)
        val name = builder.toString
        Token.keywords.get(name) match {
          case Some(Token.Record) if inPath => Token.Word(name, pos)
          case Some(Token.Enum) if inPath => Token.Word(name, pos)
          case Some(token) => token
          case None =>
            Token.Word(name, pos)
        }
      }
    }

    def ignoreLine(): Token = {
      skipLine
      toNextNonWhiteSpace()
      parseToken
    }

    def parseToken: Token = {
      val first = reader.ch
      first match {
        case ',' | '&' | '|' | '!' | '=' | '+' | '-' | '*' | '@' | ':' | '?' |
            '%' | '^' | '~' =>
          reader.nextChar()
          Token.SpecialSym
        case Chars.SU =>
          reader.nextChar()
          Token.EOF
        case '.' =>
          reader.nextChar()
          Token.Dot
        case '{' =>
          reader.nextChar()
          Token.LBrace
        case '}' =>
          reader.nextChar()
          Token.RBrace
        case ';' =>
          reader.nextChar()
          Token.Semicolon
        case '(' =>
          reader.nextChar()
          Token.LParen
        case ')' =>
          reader.nextChar()
          Token.RParen
        case '[' =>
          reader.nextChar()
          Token.LBracket
        case ']' =>
          reader.nextChar()
          Token.RBracket
        case '<' =>
          reader.nextChar()
          Token.LessThan
        case '>' =>
          reader.nextChar()
          Token.GreaterThan
        case '"' =>
          val t = quotedLiteral('"')
          reader.nextChar()
          t
        case '\'' =>
          val t = quotedLiteral('\'')
          reader.nextChar()
          t
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
              token
          }
        case '#' =>
          reader.nextChar()
          kwOrIdent(reader.endCharOffset, new StringBuilder(first + 1)) match {
            case Token.Word("include", _) => Token.IncludeHeader
            case _ => ignoreLine()
          }
        case _ =>
          val token = kwOrIdent(reader.endCharOffset, new StringBuilder(first))
          token
      }
    }

    toNextNonWhiteSpace()
    parseToken
  }

  private def readPaths: List[Token.WithPos] = {
    val builder = List.newBuilder[Token.WithPos]
    @tailrec
    def loop(): List[Token.WithPos] = {
      fetchToken(inPath = true) match {
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
      fetchToken() match {
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
    fetchToken() match {
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
    case object Enum extends Token
    case object Record extends Token
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

    val keywords: Map[String, Token] = Map(
      "package" -> Package,
      "class" -> Class,
      "interface" -> Interface,
      "record" -> Record,
      "enum" -> Enum,
      "extends" -> Extends,
      "implements" -> Implements
    )
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
