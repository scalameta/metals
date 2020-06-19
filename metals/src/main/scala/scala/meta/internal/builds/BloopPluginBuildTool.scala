package scala.meta.internal.builds
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

/**
 * Helper class file build tools that have a Bloop plugin */
abstract class BloopPluginBuildTool extends BuildTool {

  def bloopInstall(
      workspace: AbsolutePath,
      languageClient: MetalsLanguageClient,
      systemProcess: List[String] => Future[BloopInstallResult]
  ): Future[BloopInstallResult] =
    systemProcess(args(workspace))

  def args(workspace: AbsolutePath): List[String]
}
