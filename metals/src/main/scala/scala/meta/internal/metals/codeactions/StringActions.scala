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
    val position = params.getRange.getStart

    Future
      .successful {
        path.toInputFromBuffers(buffers).tokenize.toOption match {
          case Some(tokens) =>
            val lineStringTokens = tokens
              .filter(_.pos.startLine == position.getLine)
              .collect {
                case t: Token.Constant.String => t
                case s: Token.Interpolation.Start => s
                case e: Token.Interpolation.End => e
              }
              .toList
            if (lineStringTokens.isEmpty) Nil
            else List(stripMarginAction(uri, lineStringTokens))
          case None => Nil
        }
      }
  }

  def stripMarginAction(
      uri: String,
      tokens: List[Token]
  ): l.CodeAction = {
    val head = tokens.head

    val edits: List[l.TextEdit] =
      if (head.name == "interpolation start") {
        val next = tokens(1)
        val editStart =
          new l.TextEdit(head.pos.toLSP, toStripMarginStart(head.text))
        val editEnd =
          new l.TextEdit(next.pos.toLSP, toStripMarginEnd(next.text))
        editStart :: List(editEnd)
      } else {
        List(new l.TextEdit(head.pos.toLSP, toStripMargin(head.text)))
      }

    val codeAction = new l.CodeAction()

    codeAction.setTitle(StringActions.title)
    codeAction.setKind(l.CodeActionKind.Refactor)
    codeAction.setEdit(
      new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava)
    )

    codeAction
  }

  def quotify(str: String): String = str.replace("'", """"""")

  def toStripMargin(str: String): String = {
    quotify(
      str.replaceFirst(""""""", "'''|").replace(""""""", "'''.stripMargin")
    )
  }

  def toStripMarginStart(str: String): String = {
    quotify(str.replace(""""""", "'''|"))
  }

  def toStripMarginEnd(str: String): String = {
    quotify(str.replace(""""""", "'''.stripMargin"))
  }

}

object StringActions {
  def title: String = "Convert to stripMargin"
}
