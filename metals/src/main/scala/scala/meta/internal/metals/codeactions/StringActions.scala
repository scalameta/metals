package scala.meta.internal.metals.codeactions

import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.{lsp4j => l}
import scala.concurrent.{ExecutionContext, Future}
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

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
        path.toInputFromBuffers(buffers).tokenize.toOption match {
          case Some(tokens) =>
            val stringActions = tokens.collect {
              case token: Token.Constant.String
                  if (token.pos.toLSP.overlapsWith(range) && isNotTripleQuote(
                    token
                  )) =>
                stripMarginAction(uri, token.pos.toLSP)
            }.toList

            val interpolatedStringTokens = tokens.collect {
              case start: Token.Interpolation.Start
                  if (start.pos.toLSP.getStart.getCharacter <= range.getStart.getCharacter
                    && isNotTripleQuote(start)) =>
                start
              case end: Token.Interpolation.End
                  if (end.pos.toLSP.getEnd.getCharacter >= range.getEnd.getCharacter
                    && isNotTripleQuote(end)) =>
                end
            }.toList

            interpolatedStringTokens match {
              case (s: Token.Interpolation.Start) :: (e: Token.Interpolation.End) :: Nil =>
                stripMarginAction(
                  uri,
                  new l.Range(s.pos.toLSP.getStart, e.pos.toLSP.getEnd)
                ) :: stringActions
              case _ => stringActions
            }

          case None => Nil
        }
      }
  }

  def stripMarginAction(
      uri: String,
      range: l.Range
  ): l.CodeAction = {
    val start = range.getStart
    range.getStart.setCharacter(range.getStart.getCharacter + 1)
    val startRange = new l.Range(start, range.getStart)

    val end = range.getEnd
    range.getEnd.setCharacter(range.getEnd.getCharacter + 1)
    val endRange = new l.Range(end, range.getEnd)

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
