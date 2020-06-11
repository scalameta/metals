package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

final class OrganizeImports(scalafixProvider: ScalafixProvider)
    extends CodeAction {
  override def kind: String = OrganizeImports.kind

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {
    if (isSourceOrganizeImportCalled(params)) {
      val uri = params.getTextDocument.getUri
      val path = uri.toAbsolutePath
      val edits = scalafixProvider.organizeImports(path)

      val codeAction = new l.CodeAction()
      codeAction.setTitle(OrganizeImports.title)
      codeAction.setKind(l.CodeActionKind.SourceOrganizeImports)
      codeAction.setEdit(
        new l.WorkspaceEdit(
          Map(uri -> edits.asJava).asJava
        )
      )
      Future.successful {
        Seq(codeAction)
      }
    } else Future.successful(Seq())
  }

  private def isSourceOrganizeImportCalled(params: CodeActionParams): Boolean =
    Option(params.getContext.getOnly)
      .map(_.asScala.toList.contains(kind))
      .isDefined

}
object OrganizeImports {
  def title: String = "Organize imports"
  def kind: String = l.CodeActionKind.SourceOrganizeImports
}
