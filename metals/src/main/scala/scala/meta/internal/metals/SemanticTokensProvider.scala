package scala.meta.internal.metals

import scala.annotation.switch
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.matching.Regex

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsers.SoftKeywords
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.SemanticTokens._
import scala.meta.io.AbsolutePath
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
      if (lines.last.isEmpty()) delta = Line(1, 0)
      else delta = Line(0, lines.last.length())
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
      path: AbsolutePath,
      isScala3: Boolean,
      trees: Trees,
  )(implicit rc: ReportContext): List[Integer] = {
    // no semantic data was available, we can revert to default highlighting
    if (nodes.isEmpty) {
      if (params.uri().toString().isScalaFilename)
        scribe.warn("Could not find semantic tokens for: " + params.uri())
      List.empty[Integer]
    } else {
      val tokens = Try(getTokens(isScala3, params.text())) match {
        case Failure(t) =>
          rc.unsanitized.create(
            Report(
              "semantic-tokens-provider",
              params.text(),
              s"Could not find semantic tokens for: ${params.uri()}",
              Some(params.uri().toString()),
              error = Some(t),
            )
          )
          /* Try to get tokens from trees as a fallback to avoid
           * things being unhighlighted too often.
           */
          trees
            .get(path)
            .map(_.tokens)
            .getOrElse(Tokens(Array.empty))

        case Success(tokens) => tokens
      }
      val buffer = ListBuffer.empty[Integer]

      var delta = Line(0, 0)
      var nodesIterator: List[Node] = nodes
      for (tk <- tokens) {
        val (toAdd, nodesIterator0, delta0) =
          handleToken(tk, nodesIterator, isScala3, delta)
        nodesIterator = nodesIterator0
        buffer.addAll(
          toAdd
        )
        delta = delta0
      }
      buffer.toList
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
      tk: Token,
      nodesIterator: List[Node],
      isScala3: Boolean,
  ): (Int, Int, List[Node]) =
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

  private def isDocString(str: String) = {
    str.trim.split("\n").forall(_.trim.startsWith("*"))
  }

  private def handleToken(
      tk: scala.meta.tokens.Token,
      nodesIterator: List[Node],
      isScala3: Boolean,
      delta: Line,
  ): (List[Integer], List[Node], Line) = {
    tk match {
      case comm: Token.Comment if comm.value.startsWith(">") =>
        val (toAdd, delta0) = makeScalaCliTokens(comm, delta)
        (toAdd, nodesIterator, delta0)
      case comm: Token.Comment if isDocString(comm.value) =>
        val (toAdd, delta0) = makeDocStringTokens(comm, delta)
        (toAdd, nodesIterator, delta0)
      case EscapableString(text, isInterpolation) =>
        val (toAdd, delta0) = makeStringTokens(text, isInterpolation, delta)
        (toAdd, nodesIterator, delta0)
      case _ =>
        val (tokenType, tokenModifier, remainingNodes) =
          getTypeAndMod(tk, nodesIterator, isScala3)

        val (toAdd, delta0) = convertTokensToIntList(
          tk.text,
          delta,
          tokenType,
          tokenModifier,
        )
        (toAdd, remainingNodes, delta0)
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

    (tokenType, tokenModifier)
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

  private def makeScalaCliTokens(
      comm: Token.Comment,
      initialDelta: Line,
  ): (List[Integer], Line) = {
    val directive = comm.toString()
    val parts = directive.split("[,\\s]+").toList.zipWithIndex

    @tailrec
    def loop(
        parts: List[(String, Int)],
        delta: Line,
        text: String,
        tokens: List[Integer],
    ): (List[Integer], Line) = {
      parts match {
        case Nil => (tokens, delta)
        case (part, partIdx) :: next =>
          val tokenType = getUsingTokenType(part, partIdx)
          val tokenMod =
            if (tokenType == getTypeId(SemanticTokenTypes.Variable))
              1 << getModifierId(SemanticTokenModifiers.Readonly)
            else 0
          val idx = text.indexOf(part)
          val afterWhitespace = delta.moveOffset(idx)
          val (toAdd, newDelta) = convertTokensToIntList(
            part,
            afterWhitespace,
            tokenType,
            tokenMod,
          )
          val newText = text.substring(idx + part.length)
          loop(next, newDelta, newText, tokens ++ toAdd)
      }
    }
    loop(parts, initialDelta, directive, List.empty)
  }

  private def getUsingTokenType(part: String, idx: Int): Int = {
    idx match {
      case 0 =>
        if (part == "//>") getTypeId(SemanticTokenTypes.Comment) else -1
      case 1 =>
        if (part == "using") getTypeId(SemanticTokenTypes.Keyword) else -1
      case 2 => getTypeId(SemanticTokenTypes.Variable)
      case _ => getTypeId(SemanticTokenTypes.String)
    }
  }

  private def makeDocStringTokens(
      comm: Token.Comment,
      initialDelta: Line,
  ): (List[Integer], Line) = {
    val docstring = comm.toString()
    val buffer = ListBuffer.empty[Integer]

    val docstringTokens = DocstringToken.getDocstringTokens(docstring)
    var delta = initialDelta
    var currIdx = 0
    docstringTokens.foreach { tk =>
      // Comment highlight between docstring tokens
      val (commentHighlight, commentDelta) =
        convertTokensToIntList(
          docstring.substring(currIdx, tk.start),
          delta,
          getTypeId(SemanticTokenTypes.Comment),
        )
      buffer.addAll(commentHighlight)
      delta = commentDelta
      // Highlight for docstring token (@param, @throws, etc.)
      val (tokenHighlight, tokenDelta) =
        convertTokensToIntList(
          tk.text,
          delta,
          tk.tokenType,
          tk.tokenModifier,
        )

      buffer.addAll(tokenHighlight)
      delta = tokenDelta
      currIdx = tk.end
    }
    val (toAdd, delta0) =
      convertTokensToIntList(
        docstring.substring(currIdx, docstring.length),
        delta,
        getTypeId(SemanticTokenTypes.Comment),
      )
    buffer.addAll(toAdd)
    delta = delta0
    (buffer.toList, delta)
  }

  private def makeStringTokens(
      text: String,
      isInterpolation: Boolean,
      initialDelta: Line,
  ): (List[Integer], Line) = {
    val buffer = ListBuffer.empty[Integer]
    var delta = initialDelta
    val currentPart = new StringBuilder()

    def emitToken(token: String, tokenType: Int) = {
      val (toAdd, newDelta) = convertTokensToIntList(
        token,
        delta,
        tokenType,
      )
      buffer.addAll(toAdd)
      delta = newDelta
    }

    def emitCurrent() = {
      val current = currentPart.result()
      if (current.nonEmpty) {
        emitToken(current, getTypeId(SemanticTokenTypes.String))
        currentPart.clear()
      }
    }

    def emitEscape(special: String) =
      emitToken(
        special,
        getTypeId(SemanticTokenTypes.Regexp),
      )

    @tailrec
    def loop(text: List[Char]): Unit = {
      text match {
        case '\\' :: 'u' :: rest if rest.length >= 4 =>
          emitCurrent()
          emitEscape(s"\\u${rest.take(4).mkString}")
          loop(rest.drop(4))
        case '\\' :: c :: rest =>
          emitCurrent()
          emitEscape("\\" + c)
          loop(rest)
        case '$' :: '$' :: rest if isInterpolation =>
          emitCurrent()
          emitEscape("$$")
          loop(rest)
        case Nil => emitCurrent()
        case c :: rest =>
          currentPart.addOne(c)
          loop(rest)
      }
    }

    loop(text.toList)
    (buffer.result(), delta)
  }

  case class DocstringToken(
      start: Int,
      end: Int,
      text: String,
      tokenType: Integer,
      tokenModifier: Integer,
  )

  object DocstringToken {

    val paramOrThrows: String = "(@param|@tparam|@throws)\\s+([\\w.]+)"
    val apiLink: String = "\\[\\[(.*?)\\]\\]"
    val other: String = "(@[a-zA-Z]+)"
    val reg: Regex = s"$paramOrThrows|$apiLink|$other".r

    def getDocstringTokens(docstring: String): List[DocstringToken] =
      reg
        .findAllMatchIn(docstring)
        .flatMap(fromMatch)
        .toList

    def fromMatch(m: Regex.Match): List[DocstringToken] = {
      m.subgroups match {
        // @throws exception
        case "@throws" :: param :: _ if param != null =>
          val keywordToken = DocstringToken(
            m.start(1),
            m.end(1),
            "@throws",
            getTypeId(SemanticTokenTypes.Keyword),
            0,
          )
          val paramToken = DocstringToken(
            m.start(2),
            m.end(2),
            param,
            getTypeId(SemanticTokenTypes.Class),
            0,
          )
          List(keywordToken, paramToken)
        // @param param | @tparam param
        case keyword :: param :: _ if param != null && keyword != null =>
          val keywordToken = DocstringToken(
            m.start(1),
            m.end(1),
            keyword,
            getTypeId(SemanticTokenTypes.Keyword),
            0,
          )
          val paramToken = DocstringToken(
            m.start(2),
            m.end(2),
            param,
            getTypeId(SemanticTokenTypes.Variable),
            1 << getModifierId(SemanticTokenModifiers.Readonly),
          )
          List(keywordToken, paramToken)
        // [[apiLink]]
        case _ :: _ :: apiLink :: _ if apiLink != null =>
          List(
            DocstringToken(
              m.start(3),
              m.end(3),
              m.group(3),
              getTypeId(SemanticTokenTypes.String),
              0,
            )
          )
        // @return | @note | ...
        case _ :: _ :: _ :: other :: _ if other != null =>
          List(
            DocstringToken(
              m.start(4),
              m.end(4),
              m.group(4),
              getTypeId(SemanticTokenTypes.Keyword),
              0,
            )
          )
        case _ => List.empty
      }
    }
  }
}

object EscapableString {
  def unapply(token: Token): Option[(String, Boolean)] =
    token match {
      case str: Token.Constant.String if !str.text.startsWith("\"\"\"") =>
        Some((str.text, false))
      case c: Token.Constant.Char =>
        Some((c.text, false))
      case inter: Token.Interpolation.Part =>
        Some((inter.text, true))
      case _ => None
    }
}
