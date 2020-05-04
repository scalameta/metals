package scala.meta.internal.metals.codeactions

import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.{lsp4j => l}
import scala.concurrent.{ExecutionContext, Future}
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

class StringToMultiline(buffers: Buffers) extends CodeAction {

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
            tokens
              .filter(_.pos.startLine == position.getLine)
              .collect {
                case t: Token.Constant.String => simpleStringAction(uri, t)
              }
          case None => Nil
        }
      }
  }

  def simpleStringAction(
      uri: String,
      token: Token
  ): l.CodeAction = {
    val range = token.pos.toLSP
    val edit = toStripMargin(token.text)
    val textEdit = new l.TextEdit(range, quotify(edit))

    val codeAction = new l.CodeAction()

    codeAction.setTitle(StringToMultiline.title)
    codeAction.setKind(l.CodeActionKind.Refactor)
    codeAction.setEdit(
      new l.WorkspaceEdit(Map(uri -> List(textEdit).asJava).asJava)
    )

    codeAction
  }

  def quotify(str: String) = str.replace("'", """"""")

  def toStripMargin(str: String) = {
    str.replaceFirst(""""""", "'''|").replace(""""""", "'''.stripMargin")
  }

}

object StringToMultiline {
  def title: String = "Convert to stripMargin"
}
