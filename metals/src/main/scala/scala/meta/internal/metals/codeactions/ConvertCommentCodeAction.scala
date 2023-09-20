package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.parsing.Trees
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

    buffers
      .get(path)
      .toList
      .flatMap { content =>
        val tokenized =
          if (range.getStart == range.getEnd)
            Trees.defaultTokenizerDialect(content).tokenize.toOption
          else None
        tokenized.flatMap { tokens =>
          findAndExpandComment(tokens, range, isSingleLineComment(content))
            .map { changes =>
              val action = CodeActionBuilder.build(
                title = ConvertCommentCodeAction.Title,
                kind = this.kind,
                changes = List(path -> changes),
              )
              action
            }
        }.toList
      }
  }

  private def isSingleLineComment(content: String)(
      t: Token
  ): Boolean = t match {
    case tc: Token.Comment =>
      val currentLine = content.split('\n')(t.pos.startLine)
      tc.pos.startLine == tc.pos.endLine &&
      currentLine.slice(tc.pos.startColumn, tc.pos.startColumn + 2) == "//"
    case _ => false
  }

  private def findAndExpandComment(
      tokens: Tokens,
      range: l.Range,
      isSingleLineComment: Token => Boolean,
  ): Option[List[l.TextEdit]] = {
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
      expandCommentCluster(
        tokensBeforeCursor,
        tokensAfterCursor,
        range,
      )
    } else {
      None
    }
  }

  private def expandCommentCluster(
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
      .getOrElse(range.getStart)
    val commentEnd = commentAfterCursor.lastOption
      .map(_.pos.toLsp.getEnd())
      .getOrElse(range.getEnd())

    val commentTokens = commentBeforeCursor ++ commentAfterCursor
    if (commentTokens.nonEmpty) {
      val replaceText =
        commentTokens
          .map(_.value.trim())
          .mkString("/* ", "\n * ", " */")
      if (commentBeforeCursor.isEmpty) {
        // this is safe as there have to be some tokens after cursor if commentBeforeCursor is empty
        commentStart.setCharacter(tokensAfterCursor.head.pos.startColumn)
      }
      val pos = new l.Range(commentStart, commentEnd)
      Some(List(new l.TextEdit(pos, replaceText)))
    } else {
      None
    }
  }

  private def collectContinuousComments(
      tokens: Seq[Token]
  ) = {
    sealed trait State {
      def fold[T](isEmpty: => T)(nonEmpty: Seq[Token.Comment] => T): T = {
        this match {
          case State.Empty => isEmpty
          case State.Continue(acc, _) => nonEmpty(acc)
          case State.Stop(acc) => nonEmpty(acc)
        }
      }
    }
    object State {
      case object Empty extends State
      case class Continue(acc: Seq[Token.Comment], last: Token) extends State
      case class Stop(acc: Seq[Token.Comment]) extends State
    }
    tokens
      .foldLeft(State.Empty: State) {
        case (s: State.Stop, _) => s
        case (State.Empty, item) =>
          item match {
            case newComment: Token.Comment =>
              State.Continue(Seq(newComment), newComment)
            case _: Token.Whitespace => State.Empty
            case _: Token.BOF => State.Empty
            case _ => State.Stop(Seq.empty)
          }
        case (State.Continue(acc, last), item) =>
          // comments can be separated by EOL tokens and other whitespace characters
          (last, item) match {
            case (_: Token.EOL, newComment: Token.Comment) =>
              State.Continue(acc :+ newComment, newComment)
            case (_: Token.Comment, eol: Token.EOL) =>
              State.Continue(acc, eol)
            // if the new item is whitespace but not EOL then continue but keep the last token as it was
            case (_, _: Token.Whitespace) =>
              State.Continue(acc, last)
            case _ => State.Stop(acc)
          }
      }
      .fold(Seq.empty[Token.Comment])(identity)
      .toVector
  }
}

object ConvertCommentCodeAction {
  val Title: String = "Convert to multiline comment"
}
