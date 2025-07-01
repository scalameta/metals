package scala.meta.internal.builds

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import scala.meta.internal.metals.Directories
import scala.meta.io.AbsolutePath

trait BuildTool {

  def digestWithRetry(
      workspace: AbsolutePath,
      retries: Int = 1,
  ): Option[String] = {
    try {
      digest(workspace)
    } catch {
      case error: IOException =>
        scribe.warn(
          s"Failed to digest workspace $workspace for build tool $executableName with error: $error${if (retries > 0) ", will retry" else ""}"
        )
        if (retries > 0) {
          digestWithRetry(workspace, retries = 0)
        } else None
    }
  }

  def ensurePrerequisites(workspace: AbsolutePath): Unit = {}

  protected def digest(workspace: AbsolutePath): Option[String]

  protected lazy val tempDir: Path = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  def redirectErrorOutput: Boolean = false

  def executableName: String

  def projectRoot: AbsolutePath

  val forcesBuildServer = false

  def isBloopInstallProvider: Boolean = false

  /**
   * Name of the build server if different than the actual build-tool that is
   * serving as a build server.
   *
   * Ex. mill isn't mill, but rather mill-bsp
   */
  def buildServerName = executableName

  def possibleBuildServerNames: List[String] = List(buildServerName)

  def isBspGenerated(workspace: AbsolutePath): Boolean =
    possibleBuildServerNames
      .map(name => workspace.resolve(Directories.bsp).resolve(s"$name.json"))
      .exists(_.isFile)
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

  trait Verified
  case class IncompatibleVersion(buildTool: VersionRecommendation)
      extends Verified {
    def message: String = s"Unsupported $buildTool version ${buildTool.version}"
  }
  case class NoChecksum(buildTool: BuildTool, root: AbsolutePath)
      extends Verified {
    def message: String =
      s"Could not calculate checksum for ${buildTool.executableName} in $root"
  }
  case class Found(buildTool: BuildTool, digest: String) extends Verified

}
