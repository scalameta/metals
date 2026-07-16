package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalacDiagnostic.DiagnosticData
import scala.meta.pc.CancelToken

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.{lsp4j => l}

class ActionableDiagnostic() extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def createActionableDiagnostic(
        diagnostic: l.Diagnostic,
        action: Either[l.TextEdit, b.ScalaAction],
    ): l.CodeAction = {
      action match {
        case Left(textEdit) =>
          val diagMessage = diagnostic.getMessageAsString
          val uri = params.getTextDocument().getUri()

          CodeActionBuilder.build(
            title =
              s"Apply suggestion: ${diagMessage.linesIterator.headOption.getOrElse(diagMessage)}",
            kind = l.CodeActionKind.QuickFix,
            changes = List(uri.toAbsolutePath -> Seq(textEdit)),
            diagnostics = List(diagnostic),
          )
        case Right(scalaAction) =>
          val uri = params.getTextDocument().getUri()
          val edits = scalaAction.asLspTextEdits
          CodeActionBuilder.build(
            title = scalaAction.getTitle(),
            kind = l.CodeActionKind.QuickFix,
            changes = List(uri.toAbsolutePath -> edits),
            diagnostics = List(diagnostic),
          )
      }

    }

    val diagnostics =
      params.getContext().getDiagnostics().asScala.toSeq

    val codeActions = diagnostics
      .groupBy(DiagnosticData.unapply)
      .collect {
        case (Some(DiagnosticData.LegacyTextEdit(textEdit)), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
          Seq(createActionableDiagnostic(diags.head, Left(textEdit)))
        case (Some(DiagnosticData.BspActions(scalaDiagnostic)), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
          scalaDiagnostic
            .getActions()
            .asScala
            .toSeq
            .map(action =>
              createActionableDiagnostic(diags.head, Right(action))
            )
      }
      .toSeq
      .flatten
      .sorted

    val scala3CodeActions = diagnostics.flatMap {
      case diagnostic @ DiagnosticData(DiagnosticData.PcActions(actions))
          if params.getRange().overlapsWith(diagnostic.getRange()) =>
        actions
      case _ => Nil
    }

    Future.successful(codeActions ++ scala3CodeActions)
  }
}
