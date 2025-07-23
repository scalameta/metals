package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.MillifyScalaCliDependencyCodeAction._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token
import scala.meta.tokens.Tokens

import org.eclipse.{lsp4j => l}

class ConvertCommentCodeAction(buffers: Buffers, trees: Trees)
    extends CodeAction {

  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val couldBeScalaCli = path.isScalaScript || path.isScala

    (for {
      content <- buffers.get(path)
      tokens <- tokenizeIfNotRangeSelection(range, path)
      contentLines = content.split("\\r?(\\n|\\r)").toVector
      codeAction <- createActionIfPossible(
        path,
        tokens,
        range,
        isSingleLineComment(contentLines),
        isLikelyScalaCliDirective(contentLines, couldBeScalaCli),
      )
    } yield codeAction).toList
  }

  private def tokenizeIfNotRangeSelection(
      range: l.Range,
      path: AbsolutePath,
  ) = {
    Option
      .when(range.getStart == range.getEnd)(
        trees.tokenized(path)
      )
      .flatten
  }
  private def isLikelyScalaCliDirective(
      contentLines: Vector[String],
      couldBeScalaCli: Boolean,
  )(t: Token) = {
    t match {
      case tc: Token.Comment =>
        val currentLine = contentLines(tc.pos.startLine)
        couldBeScalaCli && isScalaCliUsingDirectiveComment(currentLine)
      case _ => false
    }
  }

  private def isSingleLineComment(
      contentLines: Vector[String]
  )(
      t: Token
  ): Boolean = t match {
    case tc: Token.Comment =>
      val currentLine = contentLines(t.pos.startLine)

      val tokenIsSingleLine = tc.pos.startLine == tc.pos.endLine
      val tokenStartsWithDoubleSlash =
        currentLine.slice(tc.pos.startColumn, tc.pos.startColumn + 2) == "//"

      tokenIsSingleLine &&
      tokenStartsWithDoubleSlash
    case _ => false
  }

  private def createActionIfPossible(
      path: AbsolutePath,
      tokens: Tokens,
      range: l.Range,
      isSingleLineComment: Token => Boolean,
      isLikelyScalaCliDirective: Token => Boolean,
  ): Option[l.CodeAction] = {
    val indexOfLineTokenUnderCursor = tokens.lastIndexWhere(t =>
      t.pos.encloses(range)
        && isSingleLineComment(t)
        && !isLikelyScalaCliDirective(t)
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
        isLikelyScalaCliDirective,
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
      isLikelyScalaCliDirective: Token => Boolean,
  ) = {
    val commentBeforeCursor = collectContinuousComments(
      tokens = tokensBeforeCursor.reverse,
      isLikelyScalaCliDirective,
    ).reverse

    val commentAfterCursor = collectContinuousComments(
      tokens = tokensAfterCursor,
      isLikelyScalaCliDirective,
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
      tokens: Seq[Token],
      isLikelyScalaCliDirective: Token => Boolean,
  ) = {
    tokens
      .takeWhile {
        case t: Token.Trivia if !isLikelyScalaCliDirective(t) => true
        case _ => false
      }
      .collect { case t: Token.Comment => t }
      .toVector
  }
}

object ConvertCommentCodeAction {
  val Title: String = "Convert to multiline comment"
}
