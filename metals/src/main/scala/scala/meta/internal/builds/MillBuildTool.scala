package scala.meta.internal.builds
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Properties

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

case class MillBuildTool(userConfig: () => UserConfiguration)
    extends BuildTool
    with BloopInstallProvider
    with BuildServerProvider {

  private def getMillVersion(workspace: AbsolutePath): String = {
    import scala.meta.internal.jdk.CollectionConverters._
    val millVersionPath = workspace.resolve(".mill-version")
    lazy val millPath = workspace.resolve("mill")
    if (millVersionPath.isFile) {
      Files
        .readAllLines(millVersionPath.toNIO)
        .asScala
        .headOption
        .getOrElse(version)
    } else if (millPath.isFile) {
      Files
        .readAllLines(millPath.toNIO)
        .asScala
        .find(_.startsWith("DEFAULT_MILL_VERSION"))
        .map(line => line.dropWhile(!_.isDigit).trim)
        .getOrElse(version)
    } else {
      version
    }
  }

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

  private def putTogetherArgs(cmd: List[String], millVersion: String) = {
    // In some environments (such as WSL or cygwin), mill must be run using interactive mode (-i)
    val fullcmd = if (Properties.isWin) "-i" :: cmd else cmd

    userConfig().millScript match {
      case Some(script) =>
        script :: cmd
      case None =>
        embeddedMillWrapper
          .toString() :: "--mill-version" :: millVersion :: fullcmd
    }

  }

  private def bloopImportArgs(millVersion: String) = {
    val isImportSupported = SemVer.isCompatibleVersion(
      "0.9.10",
      millVersion,
    ) && (SemVer.isLaterVersion(millVersion, "0.10.0-M1") || SemVer
      .isCompatibleVersion("0.10.0-M4", millVersion))

    if (isImportSupported)
      "--import" :: "ivy:com.lihaoyi::mill-contrib-bloop:" :: Nil
    else "--predef" :: predefScriptPath.toString :: Nil
  }

  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val millVersion = getMillVersion(workspace)
    val cmd =
      bloopImportArgs(millVersion) ::: "mill.contrib.Bloop/install" :: Nil
    putTogetherArgs(cmd, millVersion)
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    MillDigest.current(workspace)

  override def minimumVersion: String = "0.6.0"

  override def recommendedVersion: String = version

  override def version: String = BuildInfo.millVersion

  override def toString(): String = "Mill"

  override def executableName = "mill"

  private def predefScript =
    "import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`".getBytes()

  private def predefScriptPath: Path = {
    Files.write(tempDir.resolve(predefScriptName), predefScript)
  }

  override def createBspFileArgs(workspace: AbsolutePath): List[String] = {
    val cmd = "mill.bsp.BSP/install" :: Nil
    putTogetherArgs(cmd, getMillVersion(workspace))
  }

  override def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
    val minimumVersionForBsp = "0.10.0-M4"
    val millVersion = getMillVersion(workspace)

    if (SemVer.isCompatibleVersion(minimumVersionForBsp, millVersion)) {
      scribe.info(
        s"mill version ${millVersion} detected to use as a bsp server."
      )
      true
    } else {
      scribe.warn(
        s"Unable to start mill bsp server. Make sure you are using >= mill $minimumVersionForBsp."
      )
      false
    }
  }

  override val buildServerName: Option[String] = Some(MillBuildTool.name)
}

object MillBuildTool {
  val name: String = "mill-bsp"
  // Starting with 0.10.6 when using mill-bsp as a build server it automatically emits SemanticDB
  // even though it's not detected in the scalacOptions. So in this situation we don't want to warn
  // about it and trust Mill to do its job.
  val emitsSemanticDbByDefault = "0.10.6"

  def isMillRelatedPath(
      path: AbsolutePath
  ): Boolean = {
    val filename = path.toNIO.getFileName.toString
    filename.endsWith(".sc")
  }
}
