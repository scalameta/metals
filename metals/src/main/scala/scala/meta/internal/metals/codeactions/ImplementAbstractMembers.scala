package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ImplementAbstractMembers(compilers: Compilers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    Future.sequence(
      params
        .getContext()
        .getDiagnostics()
        .asScala
        .toSeq
        .collect {
          case d @ ScalacDiagnostic.ObjectCreationImpossible(_)
              if params.getRange().overlapsWith(d.getRange()) =>
            implementAbstractMembers(d, params, token)
          case d @ ScalacDiagnostic.MissingImplementation(_)
              if params.getRange().overlapsWith(d.getRange()) =>
            implementAbstractMembers(d, params, token)
          case d @ ScalacDiagnostic.DeclarationOfGivenInstanceNotAllowed(_)
              if (params.getRange().overlapsWith(d.getRange())) =>
            implementAbstractMembers(d, params, token)
        }
    )
  }

  private def implementAbstractMembers(
      diagnostic: l.Diagnostic,
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[l.CodeAction] = {
    val textDocumentPositionParams = new l.TextDocumentPositionParams(
      params.getTextDocument(),
      diagnostic.getRange.getStart(),
    )
    compilers
      .implementAbstractMembers(textDocumentPositionParams, token)
      .map { edits =>
        val uri = params.getTextDocument().getUri()

        CodeActionBuilder.build(
          title = ImplementAbstractMembers.title,
          kind = l.CodeActionKind.QuickFix,
          changes = List(uri.toAbsolutePath -> edits.asScala.toSeq),
          diagnostics = List(diagnostic),
        )
      }
  }
}

object ImplementAbstractMembers {
  def title: String = "Implement all members"
}
