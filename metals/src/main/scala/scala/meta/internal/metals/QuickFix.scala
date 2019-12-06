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
        compilers
          .autoImports(params, name, token)
          .map { imports =>
            imports.asScala.map { i =>
              val uri = params.getTextDocument().getUri()
              val edit = new l.WorkspaceEdit(Map(uri -> i.edits).asJava)

              val codeAction = new l.CodeAction()

              codeAction.setTitle(
                s"Import '$name' from package '${i.packageName}'"
              )
              codeAction.setKind(l.CodeActionKind.QuickFix)
              codeAction.setDiagnostics(List(diagnostic).asJava)
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
