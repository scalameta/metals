package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

sealed abstract class OrganizeImports(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
)(implicit ec: ExecutionContext)
    extends CodeAction {

  protected def title: String
  protected def isCallAllowed(
      file: AbsolutePath,
      params: CodeActionParams,
  ): Boolean

  def dontShowErrorToUser: Boolean = false

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {
    val uri = params.getTextDocument.getUri
    val file = uri.toAbsolutePath

    if (isCallAllowed(file, params)) {
      if (diagnostics.hasDiagnosticError(file)) {
        Future.successful(
          Seq(
            CodeActionBuilder.build(
              title = this.title,
              kind = this.kind,
              disabledReason =
                Some("Cannot organize imports if the file has an error"),
            )
          )
        )
      } else {
        val scalaTarget = for {
          buildId <- buildTargets.inverseSources(file)
          target <- buildTargets.scalaTarget(buildId)
        } yield target
        scalaTarget match {
          case Some(target) =>
            organizeImportsEdits(file, target)
          case _ => Future.successful(Seq())
        }
      }
    } else Future.successful(Seq())
  }

  private def organizeImportsEdits(
      path: AbsolutePath,
      scalaVersion: ScalaTarget,
  ): Future[Seq[l.CodeAction]] = {
    scalafixProvider
      .organizeImports(path, scalaVersion, dontShowErrorToUser)
      .map {
        case Nil => Seq.empty
        case edits =>
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
    Seq("scala", "sbt", "sc").contains(file.extension)
}

object OrganizeImports {}

class SourceOrganizeImports(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
)(implicit ec: ExecutionContext)
    extends OrganizeImports(
      scalafixProvider,
      buildTargets,
      diagnostics: Diagnostics,
    ) {

  override val kind: String = SourceOrganizeImports.kind
  override protected val title: String = SourceOrganizeImports.title

  override protected def isCallAllowed(
      file: AbsolutePath,
      params: CodeActionParams,
  ): Boolean =
    isScalaOrSbt(file) && isSourceOrganizeImportCalled(params)

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
      diagnostics: Diagnostics,
    ) {

  override val kind: String = OrganizeImportsQuickFix.kind
  override protected val title: String = OrganizeImportsQuickFix.title
  override protected def isCallAllowed(
      file: AbsolutePath,
      params: CodeActionParams,
  ): Boolean =
    params
      .getContext()
      .getDiagnostics()
      .asScala
      .collect { case ScalacDiagnostic.UnusedImport(name) => name }
      .nonEmpty

  override def dontShowErrorToUser: Boolean = true
}

object OrganizeImportsQuickFix {
  final val kind: String = l.CodeActionKind.QuickFix
  final val title: String = "Fix imports"
}
