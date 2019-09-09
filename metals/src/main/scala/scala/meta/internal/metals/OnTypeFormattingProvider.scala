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

/*in order to use onTypeFormatting in vscode,
you'll have to set editor.formatOnType = true in settings*/
final class OnTypeFormattingProvider(
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

  private def indent(toInput: String, pos: meta.Position): String = {
    val beforePos = toInput.substring(0, pos.start)
    val lastPipe = beforePos.lastIndexOf("|")
    val lastNewline = beforePos.lastIndexOf("\n", lastPipe)
    val indent = beforePos.substring(beforePos.lastIndexOf("\n")).length
    val length = toInput.substring(lastNewline, lastPipe).length
    space * (length - indent)
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

  private def pipeInScope(pos: meta.Position, text: String): Boolean = {
    val firstNewline = text.substring(0, pos.start).lastIndexOf("\n")
    val lastNewline =
      text.substring(0, firstNewline).lastIndexOf("\n")
    text
      .substring(lastNewline + 1, pos.start)
      .contains("|")
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

  def format(
      params: DocumentOnTypeFormattingParams
  ): Future[java.util.List[TextEdit]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val range = new Range(params.getPosition, params.getPosition)

    val edit = if (source.exists) {
      val sourceText = buffer.get(source).getOrElse("")
      val pos = params.getPosition.toMeta(
        Input.VirtualFile(source.toString(), sourceText)
      )
      if (pipeInScope(pos, sourceText)) {
        val tokens =
          Input.VirtualFile(source.toString(), sourceText).tokenize.toOption
        tokens.flatMap { tokens: Tokens =>
          if (multilineStringInTokens(tokens, pos, sourceText)) {
            Some(new TextEdit(range, indent(sourceText, pos) + "|"))
          } else {
            None
          }
        }
      } else None
    } else None
    Future.successful(edit.toList.asJava)
  }

}
