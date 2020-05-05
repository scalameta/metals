package scala.meta.internal.metals.codeactions

import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.{lsp4j => l}
import scala.concurrent.{ExecutionContext, Future}
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token
import scala.meta.internal.metals.Trees

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
                    if (token.pos.toLSP.overlapsWith(range)) =>
                  token
                case start: Token.Interpolation.Start
                    if (start.pos.toLSP.getStart.getCharacter <= range.getStart.getCharacter) =>
                  start
                case end: Token.Interpolation.End
                    if (end.pos.toLSP.getEnd.getCharacter >= range.getEnd.getCharacter) =>
                  end
              }
              .toList match {
              case (t: Token.Constant.String) :: _ =>
                if (isNotTripleQuote(t))
                  multilineAction(uri, t.pos.toLSP) ::
                    List(interpolationAction(uri, t.pos.toLSP))
                else List(interpolationAction(uri, t.pos.toLSP))
              case _ :: (t: Token.Constant.String) :: _ =>
                if (isNotTripleQuote(t))
                  multilineAction(uri, t.pos.toLSP) ::
                    List(interpolationAction(uri, t.pos.toLSP))
                else List(interpolationAction(uri, t.pos.toLSP))
              case (s: Token.Interpolation.Start) :: (e: Token.Interpolation.End) :: _
                  if (isNotTripleQuote(s) && isNotTripleQuote(e)) =>
                val r = new l.Range(s.pos.toLSP.getStart, e.pos.toLSP.getEnd)
                List(multilineAction(uri, r))
              case _ => Nil
            }
          case None => Nil
        }
      }
  }

  def interpolationAction(
      uri: String,
      range: l.Range
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()

    codeAction.setTitle(StringActions.interpolationTitle)
    codeAction.setKind(l.CodeActionKind.Refactor)

    val startRange = new l.Range(range.getStart, range.getStart)
    val edits = List(new l.TextEdit(startRange, "s"))

    codeAction.setEdit(new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava))

    codeAction
  }

  def multilineAction(
      uri: String,
      range: l.Range
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()

    codeAction.setTitle(StringActions.multilineTitle)
    codeAction.setKind(l.CodeActionKind.Refactor)

    val startToRight = new l.Position(
      range.getStart.getLine,
      range.getStart.getCharacter + 1
    )
    val startRange = new l.Range(startToRight, startToRight)
    val endRange = new l.Range(range.getEnd, range.getEnd)

    val edits = List(
      new l.TextEdit(startRange, "\"\"|"),
      new l.TextEdit(endRange, "\"\".stripMargin")
    )
    codeAction.setEdit(new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava))

    codeAction
  }

  def isNotTripleQuote(token: Token): Boolean =
    !(token.text.length > 2 && token.text(2) == '"')

}

object StringActions {
  def multilineTitle: String = "Convert to multiline string"
  def interpolationTitle: String = "Convert to interpolation string"
}
