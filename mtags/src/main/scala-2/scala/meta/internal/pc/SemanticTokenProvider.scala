package scala.meta.internal.pc
import java.{util => ju}

import scala.annotation.switch
import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.pc.SemanticTokens._
import scala.meta.pc.VirtualFileParams
import scala.meta.tokens._

import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file(@param params)
 *  according to the LSP specification.
 */
final class SemanticTokenProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: VirtualFileParams
) {
  import cp._

  val capableTypes = TokenTypes
  val capableModifiers = TokenModifiers
  // Alias for long notation
  val getTypeId: Map[String, Int] = capableTypes.zipWithIndex.toMap
  val getModifierId: Map[String, Int] = capableModifiers.zipWithIndex.toMap

  implicit val ord: Ordering[NodeInfo] = Ordering.fromLessThan((ni1, ni2) =>
    if (ni1.pos.start == ni2.pos.start) ni1.pos.end < ni2.pos.end
    else ni1.pos.start < ni2.pos.start
  )

  // Initialize Tree
  case class NodeInfo(
      sym: Collector.compiler.Symbol,
      pos: scala.reflect.api.Position
  )
  object Collector extends PcCollector[NodeInfo](cp, params) {

    override def collect(
        parent: Option[compiler.Tree]
    )(
        tree: compiler.Tree,
        pos: Position,
        sym: Option[compiler.Symbol]
    ): NodeInfo = {
      val symbol = sym.getOrElse(tree.symbol)
      if (symbol == null)
        NodeInfo(compiler.NoSymbol, pos)
      else
        NodeInfo(symbol, pos)
    }
  }

  val nodes: List[NodeInfo] =
    Collector.result.sorted

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

    import scala.meta._
    var cLine = Line(0, 0) // Current Line
    var lastProvided = SingleLineToken(cLine, 0, None)
    var nodesIterator: List[NodeInfo] = nodes
    for (tk <- params.text().tokenize.get) yield {

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

  // Dealing with single-line semanticToken
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
      case _ => -1
    }

  }

  /**
   * The position of @param tk must be incremented from the previous call.
   */
  def pickFromTraversed(
      tk: scala.meta.tokens.Token,
      nodesIterator: List[NodeInfo]
  ): Option[(NodeInfo, List[NodeInfo])] = {

    val adjustForBacktick: Int = {
      var ret: Int = 0
      val cName = tk.text.toCharArray()
      if (cName.size >= 2) {
        if (
          cName(0) == '`'
          && cName(cName.size - 1) == '`'
        ) ret = 2
      }
      ret
    }
    def isTarget(node: NodeInfo): Boolean =
      node.pos.start == tk.pos.start &&
        node.pos.end + adjustForBacktick == tk.pos.end &&
        node.sym != NoSymbol

    val candidates = nodesIterator.dropWhile(_.pos.start < tk.pos.start)
    candidates
      .takeWhile(_.pos.start == tk.pos.start)
      .filter(isTarget) match {
      case Nil => None
      case node :: Nil => Some((node, candidates))
      case manyNodes =>
        manyNodes.filter(ni =>
          Collector
            .symbolAlternatives(ni.sym)
            .exists(_.decodedName == tk.text)
        ) match {
          case Nil => None
          case matchingNames
              if matchingNames.exists(!_.sym.owner.isPrimaryConstructor) =>
            matchingNames.collectFirst {
              case ni if !ni.sym.owner.isPrimaryConstructor => (ni, candidates)
            }
          case head :: _ => Some((head, candidates))
        }

    }
  }

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  private def getTypeAndMod(
      tk: scala.meta.tokens.Token,
      nodesIterator: List[NodeInfo]
  ): (Int, Int, List[NodeInfo]) = {

    tk match {
      case ident: Token.Ident => IdentTypeAndMod(ident, nodesIterator)
      case _ => (typeOfNonIdentToken(tk), 0, nodesIterator)
    }
  }

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  private def IdentTypeAndMod(
      ident: Token.Ident,
      nodesIterator: List[NodeInfo]
  ): (Int, Int, List[NodeInfo]) = {
    val default = (-1, 0, nodesIterator)

    val isOperatorName = (ident.name.last: @switch) match {
      case '~' | '!' | '@' | '#' | '%' | '^' | '*' | '+' | '-' | '<' | '>' |
          '?' | ':' | '=' | '&' | '|' | '/' | '\\' =>
        true
      case _ => false
    }
    val ret =
      for (
        (nodeInfo, nodesIterator) <- pickFromTraversed(ident, nodesIterator);
        sym = nodeInfo.sym
      ) yield {
        var mod: Int = 0
        def addPwrToMod(tokenID: String) = {
          val place: Int = getModifierId(tokenID)
          if (place != -1) mod += (1 << place)
        }

        // get Type
        val typ =
          if (sym.isValueParameter)
            getTypeId(SemanticTokenTypes.Parameter)
          else if (sym.isTypeParameter || sym.isTypeSkolem)
            getTypeId(SemanticTokenTypes.TypeParameter)
          else if (isOperatorName) getTypeId(SemanticTokenTypes.Operator)
          // Java Enum
          else if (
            sym.companion
              .hasFlag(scala.reflect.internal.ModifierFlags.JAVA_ENUM)
          )
            getTypeId(SemanticTokenTypes.Enum)
          else if (sym.hasFlag(scala.reflect.internal.ModifierFlags.JAVA_ENUM))
            getTypeId(SemanticTokenTypes.EnumMember)
          // See symbol.keystring about following conditions.
          else if (sym.isJavaInterface)
            getTypeId(SemanticTokenTypes.Interface) // "interface"
          else if (sym.isTrait)
            getTypeId(SemanticTokenTypes.Interface) // "trait"
          else if (sym.isClass) getTypeId(SemanticTokenTypes.Class) // "class"
          else if (sym.isType && !sym.isParameter)
            getTypeId(SemanticTokenTypes.Type) // "type"
          else if (sym.isVariable)
            getTypeId(SemanticTokenTypes.Variable) // "var"
          else if (sym.hasPackageFlag)
            getTypeId(SemanticTokenTypes.Namespace) // "package"
          else if (sym.isModule) getTypeId(SemanticTokenTypes.Class) // "object"
          else if (sym.isSourceMethod)
            if (sym.isGetter | sym.isSetter)
              getTypeId(SemanticTokenTypes.Variable)
            else getTypeId(SemanticTokenTypes.Method) // "def"
          else if (sym.isTerm && (!sym.isParameter || sym.isParamAccessor)) {
            addPwrToMod(SemanticTokenModifiers.Readonly)
            getTypeId(SemanticTokenTypes.Variable) // "val"
          } else -1

        // Modifiers except by ReadOnly
        if (sym.isAbstract) addPwrToMod(SemanticTokenModifiers.Abstract)
        if (sym.isDeprecated) addPwrToMod(SemanticTokenModifiers.Deprecated)
        if (sym.owner.isModule) addPwrToMod(SemanticTokenModifiers.Static)

        (typ, mod, nodesIterator)

      }

    ret.getOrElse(default)

  }
}
