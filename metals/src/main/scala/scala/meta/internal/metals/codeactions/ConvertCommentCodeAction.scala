package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}
import scala.meta.tokens.Tokens
import scala.meta.tokens.Token

class ConvertCommentCodeAction(buffers: Buffers) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val tokenized =
      if (range.getStart == range.getEnd)
        buffers
          .get(path)
          .flatMap { content =>
            val currentLine = content.split('\n')(range.getStart().getLine())
            val cursorIsInTheSingleLineComment =
              currentLine.take(range.getStart().getCharacter()).contains("//")
            if (cursorIsInTheSingleLineComment) {
              Trees.defaultTokenizerDialect(content).tokenize.toOption
            } else None
          }
      else None

    tokenized
      .flatMap { tokens =>
        computeChanges(tokens, range)
          .map { changes =>
            val action = CodeActionBuilder.build(
              title = ConvertCommentCodeAction.Title,
              kind = this.kind,
              changes = List(path -> changes),
            )
            List(action)
          }
      }
      .getOrElse(List.empty)
  }

  private def computeChanges(
      tokens: Tokens,
      range: l.Range,
  ): Option[List[l.TextEdit]] = {
    val where = tokens.lastIndexWhere(t =>
      t.pos.startLine == range.getStart.getLine
        && t.pos.endLine == range.getEnd.getLine
        && t.pos.startColumn < range.getStart.getCharacter
    )
    val commentBeforeCursor = collectContinuousComments(
      tokens
        .take(where)
        .reverse
    ).reverse

    val commentAfterCursor = collectContinuousComments(tokens.drop(where))
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
          .map(_.value)
          .mkString("/*", "\n *", " */")
      commentStart.setCharacter(tokens(where).pos.startColumn)
      val pos = new l.Range(commentStart, commentEnd)
      Some(List(new l.TextEdit(pos, replaceText)))
    } else {
      None
    }
  }
  private def collectContinuousComments(tokens: Seq[Token]) = {
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
            case (_: Token.Comment, _: Token.Whitespace) =>
              State.Continue(acc, last)
            case (_: Token.EOL, _: Token.Whitespace) =>
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
