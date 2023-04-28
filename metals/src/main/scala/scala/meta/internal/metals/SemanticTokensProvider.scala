package scala.meta.internal.metals

import java.{util => ju}

import scala.annotation.switch
import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsers.SoftKeywords
import scala.meta.internal.pc.SemanticTokens._
import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams
import scala.meta.tokens._

import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file
 *  according to the LSP specification.
 */
object SemanticTokensProvider {

  def getTokens(isScala3: Boolean, text: String): Tokens = {
    import scala.meta._
    if (isScala3) {
      implicit val dialect = scala.meta.dialects.Scala3
      text.tokenize.get
    } else {
      text.tokenize.get
    }
  }

  private def convertTokensToIntList(
      text: String,
      delta0: Line,
      tokenType: Integer,
      tokenModifier: Integer = 0,
  ): (List[Integer], Line) = {
    var delta = delta0
    val buffer = ListBuffer.empty[Integer]
    if (tokenType != -1 && text.length() > 0) {
      val lines = text.split("\n", -1).toList
      lines.foreach { l =>
        if (l.length() > 0) {
          buffer.addAll(
            List(
              delta.number,
              delta.offset,
              l.length(),
              tokenType,
              tokenModifier,
            )
          )
          delta = Line(1, 0)
        } else {
          delta = delta.moveLine(1)
        }
      }
      delta = Line(0, lines.last.length())
    } else {
      val lines = text.split("\n", -1)
      if (lines.length > 1) delta = delta.moveLine(lines.length - 1)
      delta = delta.moveOffset(lines.last.length())
    }
    (buffer.toList, delta)
  }

  /**
   * Main method.  Fist, Codes are convert to Scala.Meta.Tokens.
   * And a semantic token, which is composed by 5 Ints
   * are provided for each meta-token. If a meta-token is
   * Idenitifier, the attributes (e.g. constant or not)
   * are gotten using presentation Compler.
   * All semantic tokens is flattend to a list and returned.
   */
  def provide(
      nodes: List[Node],
      params: VirtualFileParams,
      isScala3: Boolean,
  ): ju.List[Integer] = {
    // no semantic data was available, we can revert to default highlighting
    if (nodes.isEmpty) {
      scribe.warn("Could not find semantic tokens for: " + params.uri())
      List.empty[Integer].asJava
    } else {
      val text = params.text()
      val skipFistShebang =
        if (text.startsWith("#!")) text.replaceFirst("#!", "//")
        else if (text.contains("/*<script>*/#!")) // for Scala CLI scripts {
          text.replaceFirst(
            "/\\*<script>\\*/#!",
            "/*<script>*///",
          )
        else text

      val tokens = getTokens(isScala3, skipFistShebang)
      val (delta0, cliTokens, tokens0) =
        initialScalaCliTokens(tokens.toList)

      val buffer = ListBuffer.empty[Integer]
      buffer.addAll(cliTokens)

      var delta = delta0
      var nodesIterator: List[Node] = nodes
      for (tk <- tokens0) {
        val (tokenType, tokenModifier, nodesIterator0) =
          getTypeAndMod(tk, nodesIterator, isScala3)
        nodesIterator = nodesIterator0
        val (toAdd, delta0) = convertTokensToIntList(
          tk.text,
          delta,
          tokenType,
          tokenModifier,
        )
        buffer.addAll(
          toAdd
        )
        delta = delta0
      }
      buffer.toList.asJava
    }
  }

  case class Line(
      val number: Int,
      val offset: Int,
  ) {
    def moveLine(n: Int): Line = Line(number + n, 0)
    def moveOffset(off: Int): Line = Line(number, off + offset)
  }

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  private def getTypeAndMod(
      tk: scala.meta.tokens.Token,
      nodesIterator: List[Node],
      isScala3: Boolean,
  ): (Int, Int, List[Node]) = {

    tk match {
      case ident: Token.Ident if isOperatorName(ident) =>
        (getTypeId(SemanticTokenTypes.Operator), 0, nodesIterator)
      case ident: Token.Ident =>
        identTypeAndMod(ident, nodesIterator) match {
          case (-1, 0, _) =>
            val (tpe, mod) = typeModOfNonIdentToken(tk, isScala3)
            (tpe, mod, nodesIterator)
          case res => res
        }
      case _ =>
        val (tpe, mod) = typeModOfNonIdentToken(tk, isScala3)
        (tpe, mod, nodesIterator)
    }
  }

  private def bestPick(nodes: List[Node]): Option[Node] = {
    val preferred =
      nodes.maxBy(node => getTypePriority(node.tokenType))
    Some(preferred)
  }

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  private def identTypeAndMod(
      ident: Token.Ident,
      nodesIterator: List[Node],
  ): (Int, Int, List[Node]) = {
    def isTarget(node: Node): Boolean =
      node.start == ident.pos.start &&
        node.end == ident.pos.end

    val candidates = nodesIterator.dropWhile(_.start < ident.start)
    val node = candidates
      .takeWhile(_.start == ident.start)
      .filter(isTarget) match {
      case node :: Nil => Some(node)
      case Nil => None
      case manyNodes =>
        bestPick(manyNodes)
    }

    node match {
      case None => (-1, 0, candidates)
      case Some(node) => (node.tokenType(), node.tokenModifier(), candidates)
    }
  }

  /**
   * This function returns -1 when capable Type is nothing.
   *  TokenTypes that can be on multilines are handled in another func.
   *  See Token.Comment in this file.
   */
  private def typeModOfNonIdentToken(
      tk: scala.meta.tokens.Token,
      isScala3: Boolean,
  ): (Integer, Integer) = {

    val tokenType: Int =
      tk match {
        // Alphanumeric keywords
        case _: Token.ModifierKeyword => getTypeId(SemanticTokenTypes.Modifier)
        case _: Token.Keyword => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.KwNull => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.KwTrue => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.KwFalse => getTypeId(SemanticTokenTypes.Keyword)

        // extends Symbolic keywords
        case _: Token.Hash => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.Viewbound => getTypeId(SemanticTokenTypes.Operator)
        case _: Token.LeftArrow => getTypeId(SemanticTokenTypes.Operator)
        case _: Token.Subtype => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.RightArrow => getTypeId(SemanticTokenTypes.Operator)
        case _: Token.Supertype => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.At => getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.Underscore => getTypeId(SemanticTokenTypes.Variable)
        case _: Token.TypeLambdaArrow => getTypeId(SemanticTokenTypes.Operator)
        case _: Token.ContextArrow => getTypeId(SemanticTokenTypes.Operator)

        // Constant
        case _: Token.Constant.Int | _: Token.Constant.Long |
            _: Token.Constant.Float | _: Token.Constant.Double =>
          getTypeId(SemanticTokenTypes.Number)
        case _: Token.Constant.String | _: Token.Constant.Char =>
          getTypeId(SemanticTokenTypes.String)
        case _: Token.Constant.Symbol => getTypeId(SemanticTokenTypes.Property)

        // Comment
        case _: Token.Comment => getTypeId(SemanticTokenTypes.Comment)

        // Interpolation
        case _: Token.Interpolation.Id | _: Token.Interpolation.SpliceStart =>
          getTypeId(SemanticTokenTypes.Keyword)
        case _: Token.Interpolation.Start | _: Token.Interpolation.Part |
            _: Token.Interpolation.SpliceEnd | _: Token.Interpolation.End =>
          getTypeId(SemanticTokenTypes.String) // $ symbol

        case _ if isScala3 && !tk.isWhiteSpaceOrComment =>
          trySoftKeyword(tk)
        case _ => -1
      }

    val tokenModifier: Int =
      tokenType match {
        case _ if tokenType == getTypeId(SemanticTokenTypes.Variable) =>
          1 << getModifierId(SemanticTokenModifiers.Readonly)
        case _ => 0
      }

    if (tokenType != -1) (tokenType, tokenModifier)
    else if (!tk.isWhiteSpaceOrComment) {
      tokenFallback(tk)
    } else (-1, 0)

  }
  def tokenFallback(tk: scala.meta.tokens.Token): (Integer, Integer) = {
    tk.text.headOption match {
      case Some(upper) if upper.isUpper =>
        (getTypeId(SemanticTokenTypes.Class), 0)
      case Some(lower) if lower.isLower =>
        val readOnlyMod = 1 << getModifierId(SemanticTokenModifiers.Readonly)
        (getTypeId(SemanticTokenTypes.Variable), readOnlyMod)
      case _ => (-1, 0)
    }
  }

  private val SoftKeywordsUnapply = new SoftKeywords(scala.meta.dialects.Scala3)
  def trySoftKeyword(tk: Token): Integer = {
    tk match {
      case SoftKeywordsUnapply.KwAs() => getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwDerives() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwEnd() => getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwExtension() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwInfix() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwInline() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwOpaque() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwOpen() => getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwTransparent() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwUsing() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case _ => -1
    }

  }

  def isOperatorName(ident: Token.Ident): Boolean =
    (ident.name.last: @switch) match {
      case '~' | '!' | '@' | '#' | '%' | '^' | '*' | '+' | '-' | '<' | '>' |
          '?' | ':' | '=' | '&' | '|' | '/' | '\\' =>
        true
      case _ => false
    }

  def initialScalaCliTokens(
      tokens: List[Token]
  ): (Line, List[Integer], List[Token]) = {
    var delta = Line(0, 0)
    val buffer = ListBuffer.empty[Integer]
    val cliTokens = tokens.takeWhile(_.isWhiteSpaceOrComment)
    val rest = tokens.drop(cliTokens.length)
    def makeInitialTokens(tk: Token) = {
      tk match {
        case comm: Token.Comment if comm.value.startsWith(">") =>
          val cliTokens = getTokens(false, comm.value)
          cliTokens.foreach { tk =>
            val (toAdd, delta0) = tk match {
              case start: Token.Ident if start.value == ">" =>
                convertTokensToIntList(
                  "//>",
                  delta,
                  getTypeId(SemanticTokenTypes.Comment),
                )
              case using: Token.Ident if using.value == "using" =>
                convertTokensToIntList(
                  using.value,
                  delta,
                  getTypeId(SemanticTokenTypes.Keyword),
                )
              case tk =>
                val (tpe, mod) = typeModOfNonIdentToken(tk, false)
                convertTokensToIntList(
                  tk.text,
                  delta,
                  tpe,
                  mod,
                )
            }
            buffer.addAll(
              toAdd
            )
            delta = delta0
          }
        case _ =>
          val (tpe, mod) = typeModOfNonIdentToken(tk, false)
          val (toAdd, delta0) = convertTokensToIntList(
            tk.text,
            delta,
            tpe,
            mod,
          )
          buffer.addAll(
            toAdd
          )
          delta = delta0
      }
    }
    cliTokens.foreach(makeInitialTokens)
    (delta, buffer.toList, rest)
  }

}
