package scala.meta.internal.builds

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import scala.concurrent.Future

import scala.meta.internal.metals.BloopInstallResult
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

abstract class BuildTool {

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
      systemProcess: List[String] => Future[BloopInstallResult]
  ): Future[BloopInstallResult]

  def digest(workspace: AbsolutePath): Option[String]

  def version: String

  def minimumVersion: String

  def recommendedVersion: String

  def onBuildTargets(workspace: AbsolutePath, targets: BuildTargets): Unit = ()

  protected lazy val tempDir: Path = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  def redirectErrorOutput: Boolean = false

  def executableName: String

}

object BuildTool {
  def copyFromResource(
      tempDir: Path,
      filePath: String,
      destination: Option[String] = None
  ): Path = {
    val embeddedFile =
      this.getClass.getResourceAsStream(s"/$filePath")
    val outFile = tempDir.resolve(destination.getOrElse(filePath))
    Files.createDirectories(outFile.getParent)
    Files.copy(embeddedFile, outFile, StandardCopyOption.REPLACE_EXISTING)
    outFile
  }
}
