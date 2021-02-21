package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ImportMissingGiven(compilers: Compilers) extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def addGivenImport(
        diagnostic: l.Diagnostic,
        packageName: String,
        name: String
    ): Future[Seq[l.CodeAction]] =
      importMissingSymbol(diagnostic, packageName, name)

    def importMissingSymbol(
        diagnostic: l.Diagnostic,
        packageName: String,
        name: String
    ): Future[Seq[l.CodeAction]] = {
      val textDocumentPositionParams = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        diagnostic.getRange.getEnd()
      )
      compilers
        .autoImports(textDocumentPositionParams, name, token)
        .map { imports =>
          imports.asScala.filter(_.packageName() == packageName).map { i =>
            val uri = params.getTextDocument().getUri()
            val edit = new l.WorkspaceEdit(Map(uri -> i.edits).asJava)

            val codeAction = new l.CodeAction()

            codeAction.setTitle(ImportMissingGiven.title(name, i.packageName))
            codeAction.setKind(l.CodeActionKind.QuickFix)
            codeAction.setDiagnostics(List(diagnostic).asJava)
            codeAction.setEdit(edit)

            codeAction
          }
        }
    }

    def parseImport(importSuggestion: String): (String, String) = {
      val importMembers = importSuggestion.stripPrefix("import ").split(".")
      val packageName = importMembers.dropRight(1).mkString(".")
      val name = importMembers.last
      (packageName -> name)
    }

    val codeActions = params
      .getContext()
      .getDiagnostics()
      .asScala
      .collect {
        case diag @ ScalacDiagnostic.SymbolNotFoundWithImportSuggestions(
              rawSuggestions
            ) if params.getRange().overlapsWith(diag.getRange()) =>
          Future
            .traverse(rawSuggestions) { suggestion =>
              val (packageName, name) = parseImport(suggestion)
              addGivenImport(diag, packageName, name)
            }
            .map(_.flatten)
      }
      .toSeq

    Future.sequence(codeActions).map(_.flatten)
  }
}

object ImportMissingGiven {
  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"
}
