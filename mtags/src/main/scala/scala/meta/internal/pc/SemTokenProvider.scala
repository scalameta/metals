package scala.meta.internal.pc
import java.{util => ju}

import scala.annotation.switch
import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.tokens._

import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file
 *  according to the LSP specification.
 */
abstract class SemTokenProvider {
  type Sym
  type Pos

  val capableTypes = SemanticTokens.TokenTypes
  val capableModifiers = SemanticTokens.TokenModifiers
  // Alias for long notation
  val getTypeId: Map[String, Int] = capableTypes.zipWithIndex.toMap
  val getModifierId: Map[String, Int] = capableModifiers.zipWithIndex.toMap
  def getTokens(): Tokens
  def nodes: List[NodeInfo]

  /**
   * Main method.  Fist, Codes are convert to Scala.Meta.Tokens.
   * And a semantic token, which is composed by 5 Ints
   * are provided for each meta-token. If a meta-token is
   * Idenitifier, the attributes (e.g. constant or not)
   * are gotten using presentation Compler.
   * All semantic tokens is flattend to a list and returned.
   */
  def provide(): ju.List[Integer] = {

    val buffer = ListBuffer.empty[Integer]

    var cLine = Line(0, 0) // Current Line
    var lastProvided = SingleLineToken(cLine, 0, None)
    var nodesIterator: List[NodeInfo] = nodes
    for (tk <- getTokens()) yield {

      val (tokenType, tokenModifier, nodesIterator0) =
        getTypeAndMod(tk, nodesIterator)
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
                tokenModifier
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

  case class NodeInfo(
      sym: Sym,
      pos: Pos
  )

  case class Line(
      val number: Int,
      val startOffset: Int
  )
  case class SingleLineToken(
      line: Line, // line which token on
      startOffset: Int, // Offset from start of file.
      lastToken: Option[SingleLineToken]
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
      nodesIterator: List[NodeInfo]
  ): (Int, Int, List[NodeInfo]) = {

    tk match {
      case ident: Token.Ident =>
        identTypeAndMod(ident, nodesIterator) match {
          case (-1, 0, _) => (typeOfNonIdentToken(tk), 0, nodesIterator)
          case res => res
        }
      case _ => (typeOfNonIdentToken(tk), 0, nodesIterator)
    }
  }

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  protected def identTypeAndMod(
      ident: Token.Ident,
      nodesIterator: List[NodeInfo]
  ): (Int, Int, List[NodeInfo])

  /**
   * This function returns -1 when capable Type is nothing.
   *  TokenTypes that can be on multilines are handled in another func.
   *  See Token.Comment in this file.
   */
  private def typeOfNonIdentToken(
      tk: scala.meta.tokens.Token
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

      // Default
      case _ =>
        trySoftKeyword(tk)
    }

  }
  def isOperatorName(ident: Token.Ident): Boolean =
    (ident.name.last: @switch) match {
      case '~' | '!' | '@' | '#' | '%' | '^' | '*' | '+' | '-' | '<' | '>' |
          '?' | ':' | '=' | '&' | '|' | '/' | '\\' =>
        true
      case _ => false
    }

  protected def trySoftKeyword(tk: scala.meta.tokens.Token): Integer
}
