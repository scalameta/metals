package scala.meta.internal.metals.codeactions

import java.nio.file.Path
import java.util.{List => JList}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

final class OrganizeImports(
    scalafixProvider: ScalafixProvider,
    buildTargets: BuildTargets
) extends CodeAction {
  import OrganizeImports._

  override def kind: String = OrganizeImports.kind

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {
    val uri = params.getTextDocument.getUri
    val file = uri.toAbsolutePath
    if (isSourceOrganizeImportCalled(params) && isScalaOrSbt(file)) {
      scalaVersionAndClasspath(file) match {
        case Some((scalaVersion, scalaBinaryVersion, classpath))
            if !ScalaVersions.isScala3Version(scalaVersion) =>
          organizeImportsEdits(
            file,
            scalaVersion,
            scalaBinaryVersion,
            classpath
          )
        case _ => Future.successful(Seq())
      }
    } else Future.successful(Seq())

  }

  private def organizeImportsEdits(
      path: AbsolutePath,
      scalaVersion: ScalaVersion,
      scalaBinaryVersion: ScalaBinaryVersion,
      classpath: JList[Path]
  ): Future[Seq[l.CodeAction]] = {
    val edits = scalafixProvider.organizeImports(
      path,
      scalaVersion,
      scalaBinaryVersion,
      classpath
    )
    val codeAction = new l.CodeAction()
    codeAction.setTitle(OrganizeImports.title)
    codeAction.setKind(l.CodeActionKind.SourceOrganizeImports)
    codeAction.setEdit(
      new l.WorkspaceEdit(
        Map(path.toURI.toString -> edits.asJava).asJava
      )
    )
    Future.successful {
      Seq(codeAction)
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

  private def scalaVersionAndClasspath(
      file: AbsolutePath
  ): Option[(ScalaVersion, ScalaBinaryVersion, JList[Path])] =
    for {
      buildId <- buildTargets.inverseSources(file)
      scalacOptions <- buildTargets.scalacOptions(buildId)
      scalaBuildTarget <- buildTargets.scalaInfo(buildId)
      scalaVersion = scalaBuildTarget.getScalaVersion
      semanticdbTarget = scalacOptions.targetroot(scalaVersion).toNIO
      scalaBinaryVersion = scalaBuildTarget.getScalaBinaryVersion
      classPath = scalacOptions.getClasspath.map(_.toAbsolutePath.toNIO)
      _ = classPath.add(semanticdbTarget)
    } yield (scalaVersion, scalaBinaryVersion, classPath)

}
object OrganizeImports {
  def title: String = "Organize imports"
  def kind: String = l.CodeActionKind.SourceOrganizeImports

  type ScalaBinaryVersion = String
  type ScalaVersion = String
}
