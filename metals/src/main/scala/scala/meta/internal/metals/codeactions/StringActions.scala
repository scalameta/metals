package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Trees
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

import org.eclipse.{lsp4j => l}

class StringActions(buffers: Buffers) extends CodeAction {

  override def kind: String = l.CodeActionKind.Refactor

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val uri = params.getTextDocument.getUri
    val path = uri.toAbsolutePath
    val range = params.getRange

    Future
      .successful {
        val input = path.toInputFromBuffers(buffers)
        Trees.defaultDialect(input).tokenize.toOption match {
          case Some(tokens) =>
            tokens
              .filter(t =>
                t.pos.startLine == range.getStart.getLine
                  && t.pos.endLine == range.getEnd.getLine
              )
              .collect {
                case token: Token.Constant.String
                    if (token.pos.toLSP.overlapsWith(range)
                      && isNotTripleQuote(token)) =>
                  token
                case start: Token.Interpolation.Start
                    if (start.pos.toLSP.getStart.getCharacter <= range.getStart.getCharacter
                      && isNotTripleQuote(start)) =>
                  start
                case end: Token.Interpolation.End
                    if (end.pos.toLSP.getEnd.getCharacter >= range.getEnd.getCharacter
                      && isNotTripleQuote(end)) =>
                  end
              }
              .toList match {
              case (t: Token.Constant.String) :: _ =>
                List(stripMarginAction(uri, t.pos.toLSP))
              case _ :: (t: Token.Constant.String) :: _ =>
                List(stripMarginAction(uri, t.pos.toLSP))
              case (s: Token.Interpolation.Start) :: (e: Token.Interpolation.End) :: _ =>
                List(
                  stripMarginAction(
                    uri,
                    new l.Range(s.pos.toLSP.getStart, e.pos.toLSP.getEnd)
                  )
                )
              case _ =>
                Nil
            }

          case None => Nil
        }
      }
  }

  def stripMarginAction(
      uri: String,
      range: l.Range
  ): l.CodeAction = {
    range.getStart.setCharacter(range.getStart.getCharacter + 1)
    val startRange = new l.Range(range.getStart, range.getStart)

    val endRange = new l.Range(range.getEnd, range.getEnd)

    val edits = List(
      new l.TextEdit(startRange, quotify("''|")),
      new l.TextEdit(endRange, quotify("''.stripMargin"))
    )

    val codeAction = new l.CodeAction()
    codeAction.setTitle(StringActions.title)
    codeAction.setKind(l.CodeActionKind.Refactor)
    codeAction.setEdit(
      new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava)
    )

    codeAction
  }

  def quotify(str: String): String = str.replace("'", "\"")

  def isNotTripleQuote(token: Token): Boolean =
    !(token.text.length > 2 && token.text(2) == '"')

}

object StringActions {
  def title: String = "Convert to multiline string"
}
