package scala.meta.internal.builds

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.pantsbuild.BloopPants
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.BloopInstallResult
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import scala.util.Failure
import scala.util.Success
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.FutureCancelToken
import scala.meta.internal.pantsbuild.Args
import scala.meta.internal.pantsbuild.PantsConfiguration

case class PantsBuildTool(
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext)
    extends BuildTool {
  override def toString(): String = "pants"
  // NOTE(olafur) Version detection is not supported yet for Pants.
  def version: String = "1.0.0"
  def minimumVersion: String = "1.0.0"
  def recommendedVersion: String = "1.0.0"

  def executableName: String = "pants"
  def digest(workspace: AbsolutePath): Option[String] = {
    new PantsDigest(userConfig).current(workspace)
  }

  private def pantsTargets(): List[String] =
    userConfig().pantsTargets.getOrElse(Nil)

  override def onBuildTargets(
      workspace: AbsolutePath,
      buildTargets: BuildTargets
  ): Unit = {
    buildTargets.addBuildTargetInference { source =>
      // Fallback to `./pants --owner-of=$source list` when hitting on "no build target"
      if (source.isScalaScript) {
        // Convert Scala script name into a `*.scala` filename to find out what
        // target it should belong to. Pants doesn't support Scala scripts so
        // using the script name unchanged would return no targets.
        BloopPants
          .pantsOwnerOf(
            workspace,
            source.resolveSibling(_.stripSuffix(".sc") + ".scala")
          )
          .map(
            target => PantsConfiguration.toBloopBuildTarget(workspace, target)
          )
      } else if (source.isScalaOrJava) {
        BloopPants.bloopAddOwnerOf(workspace, source)
      } else {
        Nil
      }
    }
    userConfig().pantsTargets.foreach { pantsTargets =>
      PantsConfiguration
        .sourceRoots(workspace, pantsTargets)
        .foreach(root => buildTargets.addSourceRoot(root))
    }
  }

  def bloopInstall(
      workspace: AbsolutePath,
      languageClient: MetalsLanguageClient,
      systemProcess: List[String] => Future[BloopInstallResult]
  ): Future[BloopInstallResult] = {
    pantsTargets() match {
      case Nil =>
        Future.successful(BloopInstallResult.Failed(1))
      case targets =>
        Future {
          val timer = new Timer(Time.system)
          val response = languageClient.metalsSlowTask(
            Messages.bloopInstallProgress(executableName)
          )
          val token = FutureCancelToken(response.asScala.map(_.cancel))
          try {
            val args = Args().copy(
              workspace = workspace.toNIO,
              out = workspace.toNIO,
              targets = targets,
              isCache = false,
              isCompile = true
            )
            BloopPants.bloopInstall(args) match {
              case Failure(error) =>
                scribe.error(s"pants bloopInstall failed", error)
                BloopInstallResult.Failed(1)
              case Success(count) =>
                scribe.info(s"Exported ${count} Pants targets(s) in $timer")
                BloopInstallResult.Installed
            }
          } finally {
            response.cancel(false)
          }
        }
    }
  }
}

object PantsBuildTool {
  def isPantsRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath
  ): Boolean = {
    path.toNIO.endsWith("BUILD")
  }
}
