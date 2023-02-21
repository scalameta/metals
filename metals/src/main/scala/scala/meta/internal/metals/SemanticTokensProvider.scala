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

import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file
 *  according to the LSP specification.
 */
object SemanticTokensProvider {

  def getTokens(isScala3: Boolean, params: VirtualFileParams): Tokens = {
    import scala.meta._
    if (isScala3) {
      implicit val dialect = scala.meta.dialects.Scala3
      params.text().tokenize.get
    } else {
      params.text().tokenize.get
    }
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

    val buffer = ListBuffer.empty[Integer]

    var cLine = Line(0, 0) // Current Line
    var lastProvided = SingleLineToken(cLine, 0, None)
    var nodesIterator: List[Node] = nodes
    for (tk <- getTokens(isScala3, params)) yield {
      val (tokenType, tokenModifier, nodesIterator0) =
        getTypeAndMod(tk, nodesIterator, isScala3)
      nodesIterator = nodesIterator0
      var cOffset = tk.pos.start // Current Offset
      var providing = SingleLineToken(cLine, cOffset, Some(lastProvided.copy()))

      // If a meta-Token is over multiline,
      // semantic-token is provided by each line.
      // For ecample, Comment or Literal String.
      for (wkStr <- tk.text.toCharArray.toList.map(c => c.toString)) {
        cOffset += 1
        if (wkStr == "\r") providing.countCR

        // Token Break
        if (wkStr == "\n" | cOffset == tk.pos.end) {
          providing.endOffset =
            if (wkStr == "\n") cOffset - 1
            else cOffset

          if (tokenType != -1) {
            buffer.++=(
              List(
                providing.deltaLine,
                providing.deltaStartChar,
                providing.charSize,
                tokenType,
                tokenModifier,
              )
            )
            lastProvided = providing
          }
          // Line Break
          if (wkStr == "\n") {
            cLine = Line(cLine.number + 1, cOffset)
          }
          providing = SingleLineToken(cLine, cOffset, Some(lastProvided.copy()))
        }

      } // end for-wkStr

    } // end for-tk

    buffer.toList.asJava

  }

  case class Line(
      val number: Int,
      val startOffset: Int,
  )
  case class SingleLineToken(
      line: Line, // line which token on
      startOffset: Int, // Offset from start of file.
      lastToken: Option[SingleLineToken],
  ) {
    var endOffset: Int = 0
    var crCount: Int = 0
    def charSize: Int = endOffset - startOffset - crCount
    def deltaLine: Int =
      line.number - this.lastToken.map(_.line.number).getOrElse(0)

    def deltaStartChar: Int = {
      if (deltaLine == 0)
        startOffset - lastToken.map(_.startOffset).getOrElse(0)
      else
        startOffset - line.startOffset
    }
    def countCR: Unit = { crCount += 1 }
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
            (typeOfNonIdentToken(tk, isScala3), 0, nodesIterator)
          case res => res
        }
      case _ => (typeOfNonIdentToken(tk, isScala3), 0, nodesIterator)
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
  private def typeOfNonIdentToken(
      tk: scala.meta.tokens.Token,
      isScala3: Boolean,
  ): Integer = {
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

      case _ if isScala3 =>
        trySoftKeyword(tk)

      // Default
      case _ => tokenFallback(tk)
    }

  }
  def tokenFallback(tk: scala.meta.tokens.Token): Int = {
    tk.text.headOption match {
      case Some(upper) if upper.isUpper => getTypeId(SemanticTokenTypes.Class)
      case Some(lower) if lower.isLower =>
        getTypeId(SemanticTokenTypes.Variable)
      case _ => -1
    }
  }

  def trySoftKeyword(tk: Token): Integer = {
    val SoftKeywordsUnapply = new SoftKeywords(scala.meta.dialects.Scala3)
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
      case _ => tokenFallback(tk)
    }

  }

  def isOperatorName(ident: Token.Ident): Boolean =
    (ident.name.last: @switch) match {
      case '~' | '!' | '@' | '#' | '%' | '^' | '*' | '+' | '-' | '<' | '>' |
          '?' | ':' | '=' | '&' | '|' | '/' | '\\' =>
        true
      case _ => false
    }

}
