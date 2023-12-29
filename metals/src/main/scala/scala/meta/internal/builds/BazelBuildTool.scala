package scala.meta.internal.builds

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.ImportBuild
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

import coursierapi.Dependency
import coursierapi.Fetch
import org.eclipse.lsp4j.services.LanguageClient

case class BazelBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BuildServerProvider
    with VersionRecommendation {

  override def digest(workspace: AbsolutePath): Option[String] = {
    BazelDigest.current(projectRoot)
  }

  def createBspFileArgs(workspace: AbsolutePath): Option[List[String]] =
    Option.when(workspaceSupportsBsp)(composeArgs())

  def workspaceSupportsBsp: Boolean = {
    projectRoot.list.exists {
      case file if file.filename == "WORKSPACE" => true
      case _ => false
    }
  }

  private def composeArgs(): List[String] = {
    val classpathSeparator = java.io.File.pathSeparator
    val classpath = Fetch
      .create()
      .withDependencies(BazelBuildTool.dependency)
      .fetch()
      .asScala
      .mkString(classpathSeparator)
    List(
      JavaBinary(userConfig().javaHome),
      "-classpath",
      classpath,
      BazelBuildTool.mainClass,
    ) ++ BazelBuildTool.projectViewArgs(projectRoot)
  }

  override def minimumVersion: String = "3.0.0"

  override def recommendedVersion: String = version

  override def version: String = BazelBuildTool.version

  override def toString: String = "Bazel"

  override def executableName = BazelBuildTool.name

  override val forcesBuildServer = true

  override def buildServerName: String = BazelBuildTool.bspName

}

object BazelBuildTool {
  val name: String = "bazel"
  val bspName: String = "bazelbsp"
  val version: String = "3.1.0"

  val mainClass = "org.jetbrains.bsp.bazel.install.Install"

  private val dependency = Dependency.of(
    "org.jetbrains.bsp",
    "bazel-bsp",
    version,
  )

  def projectViewArgs(projectRoot: AbsolutePath): List[String] = {
    val hasProjectView =
      projectRoot.list.exists(_.filename.endsWith(".bazelproject"))
    if (hasProjectView) Nil
    else // we default to all targets view
      List(
        "-t",
        "//...",
      )
  }

  def writeBazelConfig(
      shellRunner: ShellRunner,
      projectDirectory: AbsolutePath,
      javaHome: Option[String],
  )(implicit
      ec: ExecutionContext
  ): Future[WorkspaceLoadedStatus] = {
    def run() =
      shellRunner.runJava(
        dependency,
        mainClass,
        projectDirectory,
        projectViewArgs(projectDirectory),
        javaHome,
        false,
      )
    run()
      .flatMap { code =>
        scribe.info(s"Generate Bazel-BSP process returned code $code")
        if (code != 0) run()
        else Future.successful(0)
      }
      .map {
        case ExitCodes.Success => WorkspaceLoadedStatus.Installed
        case ExitCodes.Cancel => WorkspaceLoadedStatus.Cancelled
        case result =>
          scribe.error("Failed to write Bazel-BSP config to .bsp")
          WorkspaceLoadedStatus.Failed(result)
      }
  }

  def maybeWriteBazelConfig(
      shellRunner: ShellRunner,
      projectDirectory: AbsolutePath,
      languageClient: LanguageClient,
      tables: Tables,
      javaHome: Option[String],
      forceImport: Boolean = false,
  )(implicit
      ec: ExecutionContext
  ): Future[WorkspaceLoadedStatus] = {
    val notification = tables.dismissedNotifications.ImportChanges
    if (forceImport) {
      writeBazelConfig(shellRunner, projectDirectory, javaHome)
    } else if (!notification.isDismissed) {
      languageClient
        .showMessageRequest(ImportBuild.params("Bazel"))
        .asScala
        .flatMap {
          case item if item == Messages.dontShowAgain =>
            notification.dismissForever()
            Future.successful(WorkspaceLoadedStatus.Rejected)
          case item if item == ImportBuild.yes =>
            writeBazelConfig(shellRunner, projectDirectory, javaHome)
          case _ =>
            notification.dismiss(2, TimeUnit.MINUTES)
            Future.successful(WorkspaceLoadedStatus.Rejected)

        }
    } else {
      scribe.info(
        s"skipping build import with status ${WorkspaceLoadedStatus.Dismissed}"
      )
      Future.successful(WorkspaceLoadedStatus.Dismissed)
    }
  }

  def isBazelRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean =
    path.toNIO.startsWith(workspace.toNIO) &&
      path.isBazelRelatedPath &&
      !path.isInBazelBspDirectory(workspace)
}
