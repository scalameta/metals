package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ImportMissingSymbol(compilers: Compilers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val uri = params.getTextDocument().getUri()

    def joinActionEdits(actions: Seq[l.CodeAction]) = {
      actions
        .flatMap(_.getEdit().getChanges().get(uri).asScala)
        .distinct
        .groupBy(_.getRange())
        .values
        .map(_.sortBy(_.getNewText()).reduceLeft { (l, r) =>
          l.setNewText((l.getNewText() + r.getNewText()).replace("\n\n", "\n"))
          l
        })
        .toSeq
    }

    def importMissingSymbol(
        diagnostic: l.Diagnostic,
        name: String
    ): Future[Seq[l.CodeAction]] = {
      val textDocumentPositionParams = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        diagnostic.getRange.getEnd()
      )
      compilers
        .autoImports(textDocumentPositionParams, name, token)
        .map { imports =>
          imports.asScala.map { i =>
            val uri = params.getTextDocument().getUri()
            val edit = new l.WorkspaceEdit(Map(uri -> i.edits).asJava)

            val codeAction = new l.CodeAction()

            codeAction.setTitle(ImportMissingSymbol.title(name, i.packageName))
            codeAction.setKind(l.CodeActionKind.QuickFix)
            codeAction.setDiagnostics(List(diagnostic).asJava)
            codeAction.setEdit(edit)

            codeAction
          }
        }
    }

    def importMissingSymbols(
        codeActions: Seq[l.CodeAction]
    ): Seq[l.CodeAction] = {
      val uniqueCodeActions = codeActions
        .groupBy(_.getDiagnostics())
        .values
        .filter(_.length == 1)
        .flatten
        .toSeq

      if (uniqueCodeActions.length > 1) {
        val allSymbols: l.CodeAction = new l.CodeAction()

        val diags = uniqueCodeActions.flatMap(_.getDiagnostics().asScala)
        val edits = joinActionEdits(uniqueCodeActions)

        allSymbols.setTitle(ImportMissingSymbol.allSymbolsTitle)
        allSymbols.setKind(l.CodeActionKind.QuickFix)
        allSymbols.setDiagnostics(diags.asJava)
        allSymbols.setEdit(new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava))

        allSymbols +: codeActions
      } else {
        codeActions
      }
    }

    Future
      .sequence(
        params
          .getContext()
          .getDiagnostics()
          .asScala
          .collect {
            case diag @ ScalacDiagnostic.SymbolNotFound(name)
                if params.getRange().overlapsWith(diag.getRange()) =>
              importMissingSymbol(diag, name)
          }
      )
      .map { actions =>
        val deduplicated = actions.flatten
          .groupBy(_.getTitle())
          .map {
            case (_, actions) =>
              val mainAction = actions.head
              val allDiagnostics =
                actions.flatMap(_.getDiagnostics().asScala).asJava
              val edits = joinActionEdits(actions)
              mainAction.setDiagnostics(allDiagnostics)
              mainAction
                .setEdit(new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava))
              mainAction
          }
          .toSeq
          .sorted
        importMissingSymbols(deduplicated.toSeq)
      }
  }

}

object ImportMissingSymbol {

  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  def allSymbolsTitle: String =
    s"Import all missing symbols that are unambiguous"
}
