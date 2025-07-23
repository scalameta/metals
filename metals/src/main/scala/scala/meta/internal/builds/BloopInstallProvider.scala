package scala.meta.internal.builds
import java.io.IOException
import java.nio.file.Files

import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.MetalsEnrichments._
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
      systemProcess: List[String] => CancelableFuture[WorkspaceLoadedStatus],
  ): CancelableFuture[WorkspaceLoadedStatus] = {
    cleanupStaleConfig()
    systemProcess(bloopInstallArgs(workspace))
  }

  def cleanupStaleConfig(): Unit = {
    val bloopDir = projectRoot.resolve(".bloop")
    try {
      if (bloopDir.exists && bloopDir.isDirectory) {
        bloopDir.toFile.listFiles().foreach { file =>
          if (
            file.isFile() && file.getName().endsWith(".json") && file
              .getName() != "bloop.settings.json"
          ) {
            Files.delete(file.toPath())
          }
        }
      }
    } catch {
      case _: IOException =>
        scribe.warn(
          "Failed to remove old config, bloop import might contain some stale information. Please delete `.bloop` and reimport."
        )
    }
  }

  /**
   * Args necessary for build tool to generate the .bloop files.
   */
  def bloopInstallArgs(workspace: AbsolutePath): List[String]

  override def isBloopInstallProvider: Boolean = true
}
