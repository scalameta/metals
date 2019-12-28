package scala.meta.internal.metals.codeactions

import scala.concurrent.Future
import scala.meta.pc.CancelToken
import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs

object ImportMissingSymbol extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  def label(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  override def contribute(
      params: l.CodeActionParams,
      compilers: Compilers,
      trees: Trees,
      buffers: Buffers,
      semanticdbs: Semanticdbs,
      symbolSearch: MetalsSymbolSearch,
      definitionProvider: DefinitionProvider,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

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

            codeAction.setTitle(label(name, i.packageName))
            codeAction.setKind(l.CodeActionKind.QuickFix)
            codeAction.setDiagnostics(List(diagnostic).asJava)
            codeAction.setEdit(edit)

            codeAction
          }
        }
    }

    Future
      .sequence(params.getContext().getDiagnostics().asScala.collect {
        case d @ ScalacDiagnostic.SymbolNotFound(name)
            if d.getRange().encloses(params.getRange().getEnd()) =>
          importMissingSymbol(d, name)
      })
      .map(_.flatten)

  }

}
