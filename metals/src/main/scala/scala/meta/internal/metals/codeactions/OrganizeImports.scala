package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

final class OrganizeImports(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
    languageClient: MetalsLanguageClient
)(implicit ec: ExecutionContext)
    extends CodeAction {

  override def kind: String = OrganizeImports.kind

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {
    val uri = params.getTextDocument.getUri
    val file = uri.toAbsolutePath

    if (
      explicitlyCalledAndAllowed(file, params)
      || hasUnusedImportAndAllowed(file, params)
    ) {

      val scalaTarget = for {
        buildId <- buildTargets.inverseSources(file)
        target <- buildTargets.scalaTarget(buildId)
      } yield target
      scalaTarget match {
        case Some(target) =>
          organizeImportsEdits(file, target)
        case _ => Future.successful(Seq())
      }
    } else Future.successful(Seq())

  }

  private def organizeImportsEdits(
      path: AbsolutePath,
      scalaVersion: ScalaTarget
  ): Future[Seq[l.CodeAction]] = {
    scalafixProvider
      .organizeImports(path, scalaVersion)
      .map { edits =>
        val codeAction = new l.CodeAction()
        codeAction.setTitle(OrganizeImports.title)
        codeAction.setKind(l.CodeActionKind.SourceOrganizeImports)
        codeAction.setEdit(
          new l.WorkspaceEdit(
            Map(path.toURI.toString -> edits.asJava).asJava
          )
        )
        Seq(codeAction)
      }

  }

  private def explicitlyCalledAndAllowed(
      file: AbsolutePath,
      params: CodeActionParams
  ): Boolean = {
    val validCall = isScalaOrSbt(file) && isSourceOrganizeImportCalled(params)
    if (validCall) {
      if (diagnostics.hasDiagnosticError(file)) {
        languageClient.showMessage(
          l.MessageType.Warning,
          s"Fix ${file.toNIO.getFileName} before trying to organize your imports"
        )
        scribe.info("Can not organize imports if file has error")
        false
      } else {
        true
      }
    } else {
      false
    }
  }

  private def hasUnusedImportAndAllowed(
      file: AbsolutePath,
      params: CodeActionParams
  ): Boolean = {
    val hasUnused = params
      .getContext()
      .getDiagnostics()
      .asScala
      .collect { case ScalacDiagnostic.UnusedImport(name) => name }
      .nonEmpty

    if (hasUnused && !diagnostics.hasDiagnosticError(file)) {
      true
    } else {
      false
    }
  }

  private def isSourceOrganizeImportCalled(
      params: CodeActionParams
  ): Boolean =
    Option(params.getContext.getOnly)
      .map(_.asScala.toList.contains(kind))
      .isDefined

  private def isScalaOrSbt(file: AbsolutePath): Boolean =
    Seq("scala", "sbt").contains(file.extension)

}
object OrganizeImports {
  def title: String = "Organize imports"
  def kind: String = l.CodeActionKind.SourceOrganizeImports

  type ScalaBinaryVersion = String
  type ScalaVersion = String
}
