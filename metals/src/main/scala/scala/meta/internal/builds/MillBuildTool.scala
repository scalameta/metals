package scala.meta.internal.builds
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Properties

import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

case class MillBuildTool(userConfig: () => UserConfiguration)
    extends BloopPluginBuildTool {

  private val predefScriptName = "predef.sc"

  private lazy val embeddedMillWrapper: AbsolutePath = {
    val millWrapper =
      if (Properties.isWin) "millw.bat"
      else "millw"
    val out = BuildTool.copyFromResource(tempDir, millWrapper)
    out.toFile.setExecutable(true)
    AbsolutePath(out)
  }

  override def redirectErrorOutput: Boolean = true

  override def args(workspace: AbsolutePath): List[String] = {

    import scala.meta.internal.jdk.CollectionConverters._
    val millVersionPath = workspace.resolve(".mill-version")
    val millVersion = if (millVersionPath.isFile) {
      Files
        .readAllLines(millVersionPath.toNIO)
        .asScala
        .headOption
        .getOrElse(version)
    } else {
      version
    }

    // In some environments (such as WSL or cygwin), mill must be run using interactive mode (-i)
    val iOption = if (Properties.isWin) List("-i") else Nil
    val cmd =
      iOption ::: "--predef" :: predefScriptPath(
        millVersion
      ).toString :: "mill.contrib.Bloop/install" :: Nil

    userConfig().millScript match {
      case Some(script) =>
        script :: cmd
      case None =>
        embeddedMillWrapper.toString() :: "--mill-version" :: millVersion :: cmd
    }
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    MillDigest.current(workspace)

  override def minimumVersion: String = "0.6.0"

  override def recommendedVersion: String = version

  override def version: String = "0.6.2"

  override def toString(): String = "Mill"

  def executableName = "mill"

  private def predefScript(millVersion: String) =
    "import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`".getBytes()

  private def predefScriptPath(millVersion: String): Path = {
    Files.write(tempDir.resolve(predefScriptName), predefScript(millVersion))
  }

}

object MillBuildTool {
  def isMillRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath
  ): Boolean = {
    val filename = path.toNIO.getFileName.toString
    filename.endsWith(".sc")
  }
}
