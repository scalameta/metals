package scala.meta.internal.metals

import scala.concurrent.Future
import scala.meta.pc.CancelToken
import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._

trait QuickFixes {
  trait QuickFix {
    def contribute(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]
  }

  object QuickFix {

    def fromDiagnostics(
        diagnostics: Seq[l.Diagnostic],
        params: l.CodeActionParams,
        compilers: Compilers,
        token: CancelToken
    )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] =
      Future
        .sequence(
          diagnostics
            .collect {
              case d @ ScalacDiagnostic.SymbolNotFound(name) =>
                ImportMissingSymbol(
                  name,
                  d,
                  params,
                  compilers,
                  token
                )
            }
            .map(_.contribute)
        )
        .map(_.flatten)
  }

  final case class ImportMissingSymbol(
      name: String,
      diagnostic: l.Diagnostic,
      params: l.CodeActionParams,
      compilers: Compilers,
      token: CancelToken
  ) extends QuickFix {

    override def contribute(
        implicit ec: ExecutionContext
    ): Future[Seq[l.CodeAction]] = {

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
  }
}
