package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class CreateNewSymbol() extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def createNewSymbol(
        diagnostic: l.Diagnostic,
        name: String
    ): l.CodeAction = {
      val codeAction = new l.CodeAction()
      codeAction.setTitle(CreateNewSymbol.title(name))
      codeAction.setKind(l.CodeActionKind.QuickFix)
      codeAction.setDiagnostics(List(diagnostic).asJava)
      codeAction.setCommand(ServerCommands.NewScalaFile.toLSP(List(null, name)))
      codeAction
    }

    val codeActions = params
      .getContext()
      .getDiagnostics()
      .asScala
      .groupBy {
        case ScalacDiagnostic.SymbolNotFound(name) => Some(name)
        case _ => None
      }
      .collect {
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
}
