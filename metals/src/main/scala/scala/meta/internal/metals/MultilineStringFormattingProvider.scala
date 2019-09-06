package scala.meta.internal.metals

import org.eclipse.lsp4j.{DocumentOnTypeFormattingParams, Range, TextEdit}

import scala.concurrent.{ExecutionContext, Future}
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.tokens.Token
import scala.meta.tokens.Token.Constant
import scala.meta.tokens.Tokens
import scala.meta.tokens.Token.Interpolation
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.Position

/*in order to use onTypeFormatting in vscode,
you'll have to set editor.formatOnType = true
and editor.formatOnPaste = true in settings*/
final class MultilineStringFormattingProvider(
    semanticdbs: Semanticdbs,
    buffer: Buffers
)(implicit ec: ExecutionContext) {

  private val quote = '"'
  private val space = " "
  private val stripMargin = "stripMargin"

  private def hasStripMarginSuffix(
      stringTokenIndex: Int,
      tokens: Tokens
  ): Boolean = {
    var methodIndex = stringTokenIndex + 1
    while (tokens(methodIndex).isWhiteSpaceOrComment ||
      tokens(methodIndex).isInstanceOf[Token.Dot]) methodIndex += 1
    tokens(methodIndex) match {
      case token: Token.Ident if token.value == stripMargin =>
        true
      case other =>
        false
    }
  }

  private def findChar(
      sourceText: String,
      char: Char,
      currentPos: Int,
      stop: Int = 0
  ) = {
    var start = currentPos
    while (start > stop && sourceText(start) != char) {
      start -= 1
    }
    start
  }

  private def indent(sourceText: String, start: Int): String = {
    val lastPipe = findChar(sourceText, '|', start)
    val lastNewline = findChar(sourceText, '\n', lastPipe)
    space * (lastPipe - lastNewline - 1)
  }

  private def isMultilineString(text: String, token: Token) = {
    val start = token.start
    text(start) == quote &&
    text(start + 1) == quote &&
    text(start + 2) == quote
  }

  private def inToken(pos: meta.Position, token: Token): Boolean = {
    pos.start >= token.start && pos.end <= token.end
  }

  private def pipeInScope(
      pos: meta.Position,
      text: String,
      newlineAdded: Boolean
  ): Boolean = {
    val firstNewline = findChar(text, '\n', pos.start - 1)
    val stop =
      if (newlineAdded) findChar(text, '\n', firstNewline - 1) else firstNewline
    val lastPipe = findChar(text, '|', pos.start, stop)
    lastPipe > stop
  }

  private def multilineStringInTokens(
      tokens: Tokens,
      pos: meta.Position,
      sourceText: String
  ): Boolean = {
    var tokenIndex = 0
    var stringFound = false
    var shouldAddPipes = false
    while (!stringFound && tokenIndex < tokens.size) {
      tokens(tokenIndex) match {
        case token: Constant.String if inToken(pos, token) =>
          stringFound = true
          shouldAddPipes = isMultilineString(sourceText, token) &&
            hasStripMarginSuffix(tokenIndex, tokens)
        case start: Interpolation.Start if start.start < pos.start =>
          var endIndex = tokenIndex + 1
          while (!tokens(endIndex)
              .isInstanceOf[Interpolation.End]) endIndex += 1
          val end = tokens(endIndex)
          stringFound = end.end > pos.end
          shouldAddPipes = stringFound && isMultilineString(sourceText, start) &&
            hasStripMarginSuffix(endIndex, tokens)
        case _ =>
      }
      tokenIndex += 1
    }
    shouldAddPipes
  }

  private def withToken(
      textId: TextDocumentIdentifier,
      range: Range,
      newlineAdded: Boolean
  )(
      fn: (String, meta.Position) => List[TextEdit]
  ): Future[List[TextEdit]] = Future {
    val source = textId.getUri.toAbsolutePath
    if (source.exists) {
      val sourceText = buffer.get(source).getOrElse("")
      val pos = range.getStart.toMeta(
        Input.VirtualFile(source.toString(), sourceText)
      )
      if (pipeInScope(pos, sourceText, newlineAdded)) {
        val tokens =
          Input.VirtualFile(source.toString(), sourceText).tokenize.toOption
        tokens.toList.flatMap { tokens: Tokens =>
          if (multilineStringInTokens(tokens, pos, sourceText))
            fn(sourceText, pos)
          else Nil
        }
      } else Nil
    } else Nil
  }

  def format(
      params: DocumentOnTypeFormattingParams
  ): Future[List[TextEdit]] = {
    val range = new Range(params.getPosition, params.getPosition)
    withToken(
      params.getTextDocument(),
      range,
      params.getCh() == "\n"
    ) { (sourceText, position) =>
      List(new TextEdit(range, indent(sourceText, position.start) + "|"))
    }
  }

  def format(
      params: DocumentRangeFormattingParams
  ): Future[List[TextEdit]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val range = params.getRange()

    withToken(
      params.getTextDocument(),
      range,
      newlineAdded = false
    ) { (sourceText, position) =>
      val newText = indent(sourceText, position.start) + "|"
      val lines = (range.getStart().getLine() + 1) to range.getEnd().getLine()
      lines.map { line =>
        val pos = new Position(line, 0)
        new TextEdit(new Range(pos, pos), newText)
      }.toList
    }
  }
}
