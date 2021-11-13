package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class CreateNewSymbol() extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    lazy val parentUri =
      params.getTextDocument.getUri.toAbsolutePath.parent.toURI

    def createNewSymbol(
        diagnostic: l.Diagnostic,
        name: String,
    ): l.CodeAction = {
      val command =
        ServerCommands.NewScalaFile.toLsp(parentUri.toString(), name)

      CodeActionBuilder.build(
        title = CreateNewSymbol.title(name),
        kind = l.CodeActionKind.QuickFix,
        command = Some(command),
        diagnostics = List(diagnostic),
      )
    }

    def createNewMethod(
        diagnostic: l.Diagnostic,
        name: String,
    ): l.CodeAction = {
      val codeAction = new l.CodeAction()
      codeAction.setTitle(CreateNewSymbol.method(name))
      codeAction.setKind(l.CodeActionKind.QuickFix)
      codeAction.setDiagnostics(List(diagnostic).asJava)
      codeAction.setCommand(
        ServerCommands.InsertInferredMethod.toLSP(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            params.getRange().getStart(),
          )
        )
      )
      codeAction
    }

    val codeActions = params
      .getContext()
      .getDiagnostics()
      .asScala
      .groupBy {
        case ScalacDiagnostic.SymbolNotFound(name) if name.nonEmpty =>
          Some(name)
        case _ => None
      }
      .collect {
        case (Some(name), diags)
            if name.head.isLower && params
              .getRange()
              .overlapsWith(diags.head.getRange()) =>
          createNewMethod(diags.head, name)
        case (Some(name), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
          createNewSymbol(diags.head, name)
      }
      .toSeq
      .sorted

    Future.successful(codeActions)
  }
}

object CreateNewSymbol {
  def title(name: String): String = s"Create new symbol '$name'..."
  def method(name: String): String = s"Create new method 'def $name'..."
}
