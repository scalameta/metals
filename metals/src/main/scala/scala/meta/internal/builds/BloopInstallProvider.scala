package scala.meta.internal.builds
import scala.concurrent.Future

import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

/**
 * Helper trait for build tools that have a Bloop plugin
 */
trait BloopInstallProvider { this: BuildTool =>

  /**
   * Method used to generate the necesary .bloop files for the
   * build tool.
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
