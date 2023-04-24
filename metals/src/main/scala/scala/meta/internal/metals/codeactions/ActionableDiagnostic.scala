package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.io.AbsolutePath
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
        changes: Map[AbsolutePath, List[l.TextEdit]],
    ): l.CodeAction = {
      val diagMessage = diagnostic.getMessage

      CodeActionBuilder.build(
        title =
          s"Apply suggestion: ${diagMessage.linesIterator.headOption.getOrElse(diagMessage)}",
        kind = l.CodeActionKind.QuickFix,
        changes = changes.toSeq,
        diagnostics = List(diagnostic),
      )
    }

    val codeActions: List[l.CodeAction] = params
      .getContext()
      .getDiagnostics()
      .asScala
      .groupBy {
        case ScalacDiagnostic.DiagnosticData(data) =>
          Some(data)
        case _ =>
          None
      }
      .collect {
        case (Some(Left(textEdit)), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
          List(
            createActionableDiagnostic(
              diags.head,
              Map(
                params.getTextDocument().getUri().toAbsolutePath -> List(
                  textEdit
                )
              ),
            )
          )
        case (Some(Right(edits)), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
          for {
            edit <- edits.edits.asScala.toList
            changes <- edit.getChanges().asScala.toMap.map {
              case (uri, edits) =>
                Map(uri.toAbsolutePath -> edits.asScala.toList)
            }
          } yield createActionableDiagnostic(diags.head, changes)
      }
      .toList
      .flatten
      .sorted

    Future.successful(codeActions)
  }
}
