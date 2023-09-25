package scala.meta.internal.builds

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import scala.concurrent.Future

import scala.meta.io.AbsolutePath

trait BuildTool {

  /**
   * Export the build to Bloop
   *
   * This operation should be roughly equivalent to running `sbt bloopInstall`
   * and should work for both updating an existing Bloop build or creating a new
   * Bloop build.
   */
  def bloopInstall(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[WorkspaceLoadedStatus],
  ): Future[WorkspaceLoadedStatus]

  def digest(workspace: AbsolutePath): Option[String]

  def version: String

  def minimumVersion: String

  def recommendedVersion: String

  protected lazy val tempDir: Path = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  def redirectErrorOutput: Boolean = false

  def executableName: String

  def isBloopDefaultBsp = true

  def projectRoot: AbsolutePath

}

object BuildTool {
  def copyFromResource(
      tempDir: Path,
      filePath: String,
      destination: Option[String] = None,
  ): Path = {
    val embeddedFile =
      this.getClass.getResourceAsStream(s"/$filePath")
    val outFile = tempDir.resolve(destination.getOrElse(filePath))
    Files.createDirectories(outFile.getParent)
    Files.copy(embeddedFile, outFile, StandardCopyOption.REPLACE_EXISTING)
    outFile
  }

}
