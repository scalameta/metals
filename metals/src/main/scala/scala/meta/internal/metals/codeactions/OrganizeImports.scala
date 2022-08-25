package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

sealed abstract class OrganizeImports(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext)
    extends CodeAction {

  protected def title: String
  protected def isCallAllowed(
      file: AbsolutePath,
      params: CodeActionParams,
  ): Boolean
  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {
    val uri = params.getTextDocument.getUri
    val file = uri.toAbsolutePath

    if (isCallAllowed(file, params)) {
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
      scalaVersion: ScalaTarget,
  ): Future[Seq[l.CodeAction]] = {
    scalafixProvider
      .organizeImports(path, scalaVersion)
      .map { edits =>
        Seq(
          CodeActionBuilder.build(
            title = this.title,
            kind = this.kind,
            changes = List(path.toURI.toAbsolutePath -> edits),
          )
        )
      }
  }

  protected def isScalaOrSbt(file: AbsolutePath): Boolean =
    Seq("scala", "sbt").contains(file.extension)
}

object OrganizeImports {}

class SourceOrganizeImports(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
    languageClient: MetalsLanguageClient,
)(implicit ec: ExecutionContext)
    extends OrganizeImports(
      scalafixProvider,
      buildTargets,
    ) {

  override val kind: String = SourceOrganizeImports.kind
  override protected val title: String = SourceOrganizeImports.title

  override protected def isCallAllowed(
      file: AbsolutePath,
      params: CodeActionParams,
  ): Boolean = {
    val validCall = isScalaOrSbt(file) && isSourceOrganizeImportCalled(params)
    if (validCall) {
      if (diagnostics.hasDiagnosticError(file)) {
        languageClient.showMessage(
          l.MessageType.Warning,
          s"Fix ${file.toNIO.getFileName} before trying to organize your imports",
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

  protected def isSourceOrganizeImportCalled(
      params: CodeActionParams
  ): Boolean =
    Option(params.getContext.getOnly)
      .map(_.asScala.toList.contains(kind))
      .isDefined
}

object SourceOrganizeImports {
  final val kind: String = l.CodeActionKind.SourceOrganizeImports
  final val title: String = "Organize imports"
}

class OrganizeImportsQuickFix(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
)(implicit ec: ExecutionContext)
    extends OrganizeImports(
      scalafixProvider,
      buildTargets,
    ) {

  override val kind: String = OrganizeImportsQuickFix.kind
  override protected val title: String = OrganizeImportsQuickFix.title
  override protected def isCallAllowed(
      file: AbsolutePath,
      params: CodeActionParams,
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

}

object OrganizeImportsQuickFix {
  final val kind: String = l.CodeActionKind.QuickFix
  final val title: String = "Fix imports"
}
