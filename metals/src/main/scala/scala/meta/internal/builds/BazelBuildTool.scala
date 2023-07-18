package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import coursierapi.Dependency
import scala.meta.internal.metals.Messages.ImportBuild
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Tables
import scala.meta.internal.process.ExitCodes
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.services.LanguageClient

case class BazelBuildTool(userConfig: () => UserConfiguration)
    extends BuildTool
    with BuildServerProvider {

  override def digest(workspace: AbsolutePath): Option[String] = {
    BazelDigest.current(workspace)
  }

  def createBspFileArgs(workspace: AbsolutePath): List[String] =
    BazelBuildTool.coursierArgs

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
    workspace.listRecursive.exists {
      case file if file.filename == "WORKSPACE" => true
      case _ => false
    }
  }

  override def minimumVersion: String = "1.0.0"

  override def recommendedVersion: String = version

  override def version: String = "2.7.1"

  override def toString: String = "Bazel"

  override def executableName = BazelBuildTool.name

  override def isBloopDefaultBsp = false

  override val buildServerName: Option[String] = Some(BazelBuildTool.bspName)

}

object BazelBuildTool {
  val name: String = "bazel"
  val bspName: String = "bazelbsp"

  private val coursierArgs = List(
    "cs", "launch", "org.jetbrains.bsp:bazel-bsp:2.7.1", "-M",
    "org.jetbrains.bsp.bazel.install.Install",
  )

  private val dependency = Dependency.of(
    "org.jetbrains.bsp",
    "bazel-bsp",
    "2.7.1",
  )

  private val mainClass = "org.jetbrains.bsp.bazel.install.Install"

  def writeBazelConfig(
      shellRunner: ShellRunner,
      projectDirectory: AbsolutePath,
  )(implicit
      ec: ExecutionContext
  ) = {
    def run() =
      shellRunner.runJava(dependency, mainClass, projectDirectory, Nil, false)
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
      forceImport: Boolean = false,
  )(implicit
      ec: ExecutionContext
  ) = {
    val notification = tables.dismissedNotifications.ImportChanges
    if (forceImport) {
      writeBazelConfig(shellRunner, projectDirectory)
    } else if (!notification.isDismissed) {
      languageClient
        .showMessageRequest(ImportBuild.params(name))
        .asScala
        .flatMap {
          case item if item == Messages.dontShowAgain =>
            notification.dismissForever()
            Future.successful(WorkspaceLoadedStatus.Rejected)
          case item if item == ImportBuild.yes =>
            writeBazelConfig(shellRunner, projectDirectory)
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
      path: AbsolutePath
  ): Boolean = path.isBazelRelatedPath
}
