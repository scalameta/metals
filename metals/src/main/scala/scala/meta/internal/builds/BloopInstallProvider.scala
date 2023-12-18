package scala.meta.internal.builds
import scala.concurrent.Future

import scala.meta.io.AbsolutePath

/**
 * Helper trait for build tools that have a Bloop plugin
 */
trait BloopInstallProvider extends BuildTool {

  /**
   * Method used to generate the necessary .bloop files for the
   * build tool.
   */
  def bloopInstall(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[WorkspaceLoadedStatus],
  ): Future[WorkspaceLoadedStatus] =
    systemProcess(bloopInstallArgs(workspace))

  /**
   * Args necessary for build tool to generate the .bloop files.
   */
  def bloopInstallArgs(workspace: AbsolutePath): List[String]

  override val isBloopInstallProvider = true
}
