package scala.meta.internal.metals.codeactions

import scala.concurrent.Future
import scala.meta.pc.CancelToken
import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._

class ImplementAbstractMembers(compilers: Compilers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    Future.sequence(
      params
        .getContext()
        .getDiagnostics()
        .asScala
        .collect {
          case d @ ScalacDiagnostic.ObjectCreationImpossible(_)
              if params.getRange().overlapsWith(d.getRange()) =>
            implementAbstractMembers(d, params, token)
          case d @ ScalacDiagnostic.MissingImplementation(_)
              if params.getRange().overlapsWith(d.getRange()) =>
            implementAbstractMembers(d, params, token)
        }
    )
  }

  private def implementAbstractMembers(
      diagnostic: l.Diagnostic,
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[l.CodeAction] = {
    val textDocumentPositionParams = new l.TextDocumentPositionParams(
      params.getTextDocument(),
      diagnostic.getRange.getStart()
    )
    compilers
      .implementAbstractMembers(textDocumentPositionParams, token)
      .map { edits =>
        val uri = params.getTextDocument().getUri()
        val edit = new l.WorkspaceEdit(Map(uri -> edits).asJava)

        val codeAction = new l.CodeAction()

        codeAction.setTitle(ImplementAbstractMembers.title)
        codeAction.setKind(l.CodeActionKind.QuickFix)
        codeAction.setDiagnostics(List(diagnostic).asJava)
        codeAction.setEdit(edit)

        codeAction
      }
  }
}

object ImplementAbstractMembers {
  def title: String =
    s"Implement methods"
}
