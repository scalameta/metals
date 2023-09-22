package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token
import scala.meta.tokens.Tokens

import org.eclipse.{lsp4j => l}

class ConvertCommentCodeAction(buffers: Buffers) extends CodeAction {

  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    (for {
      content <- buffers.get(path)
      tokens <- tokenizeIfNotRangeSelection(range, content)
      codeAction <- createActionIfPossible(
        path,
        tokens,
        range,
        isSingleLineComment(content.split("\\r?\\n").toVector),
      )
    } yield codeAction).toList
  }

  private def tokenizeIfNotRangeSelection(range: l.Range, content: String) = {
    Option
      .when(range.getStart == range.getEnd)(
        Trees.defaultTokenizerDialect(content).tokenize
      )
      .flatMap(_.toOption)
  }

  private def isSingleLineComment(contentLines: Vector[String])(
      t: Token
  ): Boolean = t match {
    case tc: Token.Comment =>
      val currentLine = contentLines(t.pos.startLine)
      tc.pos.startLine == tc.pos.endLine &&
      currentLine.slice(tc.pos.startColumn, tc.pos.startColumn + 2) == "//"
    case _ => false
  }

  private def createActionIfPossible(
      path: AbsolutePath,
      tokens: Tokens,
      range: l.Range,
      isSingleLineComment: Token => Boolean,
  ): Option[l.CodeAction] = {
    val indexOfLineTokenUnderCursor = tokens.lastIndexWhere(t =>
      t.pos.startLine == range.getStart.getLine
        && t.pos.endLine == t.pos.startLine
        && t.pos.startColumn < range.getStart.getCharacter
        && isSingleLineComment(t)
    )
    if (indexOfLineTokenUnderCursor != -1) {
      // tokens that are strictly before cursor, i.e. they end before cursor position
      val tokensBeforeCursor = tokens.take(indexOfLineTokenUnderCursor)
      // token under the cursor + following tokens
      val tokensAfterCursor = tokens.drop(indexOfLineTokenUnderCursor)
      val textEdit = createTextEdit(
        tokensBeforeCursor,
        tokensAfterCursor,
        range,
      )
      Some(
        CodeActionBuilder.build(
          title = ConvertCommentCodeAction.Title,
          kind = this.kind,
          changes = List(path -> List(textEdit)),
        )
      )
    } else {
      None
    }
  }

  private def createTextEdit(
      tokensBeforeCursor: Tokens,
      tokensAfterCursor: Tokens,
      range: l.Range,
  ) = {
    val commentBeforeCursor = collectContinuousComments(
      tokens = tokensBeforeCursor.reverse
    ).reverse

    val commentAfterCursor = collectContinuousComments(
      tokens = tokensAfterCursor
    )
    val commentStart = commentBeforeCursor.headOption
      .map(_.pos.toLsp.getStart)
      .getOrElse(tokensAfterCursor.head.pos.toLsp.getStart)
    val commentEnd = commentAfterCursor.lastOption
      .map(_.pos.toLsp.getEnd())
      .getOrElse(range.getEnd())

    val commentTokens = commentBeforeCursor ++ commentAfterCursor
    val replaceText =
      commentTokens
        .map(_.value.trim()) // TODO replace with strip once we drop jdk 8
        .mkString("/* ", "\n * ", " */")
    val pos = new l.Range(commentStart, commentEnd)
    new l.TextEdit(pos, replaceText)
  }

  private def collectContinuousComments(
      tokens: Seq[Token]
  ) = {
    tokens
      .takeWhile {
        case _: Token.Trivia => true
        case _ => false
      }
      .collect { case t: Token.Comment => t }
      .toVector
  }
}

object ConvertCommentCodeAction {
  val Title: String = "Convert to multiline comment"
}
