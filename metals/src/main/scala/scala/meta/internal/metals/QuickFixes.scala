package scala.meta.internal.metals

import scala.concurrent.Future
import scala.meta.pc.CancelToken
import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.lsp4j.{CodeAction, CodeActionParams}

trait QuickFix {
  def contribute(
      params: l.CodeActionParams,
      compilers: Compilers,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]
}

object QuickFix {

  object ImportMissingSymbol extends QuickFix {

    override def contribute(
        params: CodeActionParams,
        compilers: Compilers,
        token: CancelToken
    )(implicit ec: ExecutionContext): Future[Seq[CodeAction]] = {

      def importMissingSymbol(
          diagnostic: l.Diagnostic,
          name: String
      ): Future[Seq[CodeAction]] = {

        // TODO(gabro): this is hack. Instead of computing the auto-imports for a name at a range,
        // we run completions starting from the end of the range, and filter the completions that
        // match exactly the name we're looking for
        val completionParams = new l.CompletionParams(
          params.getTextDocument(),
          params.getRange().getEnd()
        )
        compilers.completions(completionParams, token).map { completions =>
          scribe.info(completions.getItems().toString())
          scribe.info(name)
          completions.getItems().asScala.collect {
            case completionItem if completionItem.getFilterText() == name =>
              val pkg = completionItem.getDetail().trim()
              val edit = new l.WorkspaceEdit()
              val uri = params.getTextDocument().getUri()
              val changes = Map(uri -> completionItem.getAdditionalTextEdits())

              val codeAction = new l.CodeAction()
              codeAction.setTitle(s"Import '$name' from package '$pkg'")
              codeAction.setKind(l.CodeActionKind.QuickFix)
              codeAction.setDiagnostics(List(diagnostic).asJava)

              edit.setChanges(changes.asJava)
              codeAction.setEdit(edit)
              codeAction
          }
        }
      }

      Future
        .sequence(params.getContext().getDiagnostics().asScala.collect {
          case d @ ScalacDiagnostic.SymbolNotFound(name) =>
            importMissingSymbol(d, name)
        })
        .map(_.flatten)

    }

  }
}
