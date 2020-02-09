package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.meta.pc.CancelToken
import org.eclipse.{lsp4j => l}
import scala.concurrent.Future
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._

class GenerateMissingMembers(compilers: Compilers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def generateStubs(
        diagnostic: l.Diagnostic
    ): Future[Option[l.CodeAction]] = {

      val textDocumentPositionParams = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        diagnostic.getRange.getEnd()
      )

      val codeAction = new l.CodeAction()

      codeAction.setTitle(GenerateMissingMembers.title)
      codeAction.setKind(l.CodeActionKind.QuickFix)
      codeAction.setDiagnostics(List(diagnostic).asJava)

      compilers
        .stubsForMissingMembers(textDocumentPositionParams, token)
        .map(v => {
          v.map(edit => {
            val uri = params.getTextDocument().getUri()
            val wsEdit =
              new l.WorkspaceEdit(Map(uri -> List(edit).asJava).asJava)
            codeAction.setEdit(wsEdit)
            codeAction
          })
        })
    }

    Future
      .sequence(
        params
          .getContext()
          .getDiagnostics()
          .asScala
          .collect({
            case d @ ScalacDiagnostic.UnimplementedMembers(_)
                if params.getRange().overlapsWith(d.getRange()) =>
              generateStubs(d)
          })
      )
      .map(_.flatten)
  }
}

object GenerateMissingMembers {

  val title = "Generate missing members"

}
