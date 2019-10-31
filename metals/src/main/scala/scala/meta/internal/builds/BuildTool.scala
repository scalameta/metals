package scala.meta.internal.builds

import java.nio.file.{Files, Path}
import scala.meta.internal.metals._
import scala.meta.io.AbsolutePath
import scala.util.Try

abstract class BuildTool {
  def args(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): List[String]

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

}

object BuildTool {

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {
    (minimumVersion.split('.'), version.split('.')) match {
      case (Array(minMajor, minMinor, minPatch), Array(major, minor, patch)) =>
        Try {
          (major > minMajor) ||
          (major == minMajor && minor > minMinor) ||
          (major == minMajor && minor == minMinor && patch >= minPatch)
        }.getOrElse(false)
      case _ => false
    }
  }

  def copyFromResource(
      tempDir: Path,
      filePath: String,
      destination: Option[String] = None
  ): Path = {
    val embeddedFile =
      this.getClass.getResourceAsStream(s"/$filePath")
    val outFile = tempDir.resolve(destination.getOrElse(filePath))
    Files.createDirectories(outFile.getParent)
    Files.copy(embeddedFile, outFile)
    outFile
  }
}
