package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ActionableDiagnostic() extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def createActionableDiagnostic(
        diagnostic: l.Diagnostic,
        textEdit: l.TextEdit,
    ): l.CodeAction = {
      val diagMessage = diagnostic.getMessage
      val uri = params.getTextDocument().getUri()

      CodeActionBuilder.build(
        title =
          s"Apply suggestion: ${diagMessage.linesIterator.headOption.getOrElse(diagMessage)}",
        kind = l.CodeActionKind.QuickFix,
        changes = List(uri.toAbsolutePath -> Seq(textEdit)),
        diagnostics = List(diagnostic),
      )
    }

    val codeActions = params
      .getContext()
      .getDiagnostics()
      .asScala
      .groupBy {
        case ScalacDiagnostic.ScalaAction(textEdit) =>
          Some(textEdit)
        case _ => None
      }
      .collect {
        case (Some(textEdit), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
          createActionableDiagnostic(diags.head, textEdit)
      }
      .toList
      .sorted

    Future.successful(codeActions)
  }
}
