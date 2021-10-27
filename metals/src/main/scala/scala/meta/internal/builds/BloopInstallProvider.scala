package scala.meta.internal.builds
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

/**
 * Helper trait for build tools that have a Bloop plugin
 */
trait BloopInstallProvider extends BuildTool { this: BuildTool =>

  /**
   * Export the build to Bloop
   *
   * This operation should be roughly equivalent to running `sbt bloopInstall`
   * and should work for both updating an existing Bloop build or creating a new
   * Bloop build.
   */
  def bloopInstall(
      workspace: AbsolutePath,
      languageClient: MetalsLanguageClient,
      systemProcess: List[String] => Future[WorkspaceLoadedStatus]
  ): Future[WorkspaceLoadedStatus] =
    systemProcess(bloopInstallArgs(workspace))

  /**
   * Args necessary for build tool to generate the .bloop files.
   */
  def bloopInstallArgs(workspace: AbsolutePath): List[String]
}
