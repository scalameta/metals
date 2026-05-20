package scala.meta.internal.builds
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.Properties

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

case class MillBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BloopInstallProvider
    with BuildServerProvider
    with VersionRecommendation {

  private[builds] def getMillVersion(workspace: AbsolutePath): String = {
    import scala.meta.internal.jdk.CollectionConverters._
    val millVersionPath = workspace.resolve(".mill-version")
    lazy val altMillVersionPath =
      workspace.resolve(".config").resolve("mill-version")
    lazy val buildFile = workspace.resolve("build.mill")
    lazy val buildFile1 = workspace.resolve("build.mill.scala")
    lazy val buildFile2 = workspace.resolve("build.sc")
    lazy val buildFileYaml = workspace.resolve("build.mill.yaml")
    lazy val millPath = workspace.resolve("mill")

    lazy val versionFromBuildfiles = LazyList(buildFile, buildFile1, buildFile2)
      .find(_.isFile)
      .flatMap(readMillVersionYamlFrontmatter(_))

    lazy val versionFromYamlFile =
      if (buildFileYaml.isFile) readMillVersionYaml(buildFileYaml) else None

    def readMillVersion(path: AbsolutePath) =
      Files
        .readAllLines(path.toNIO)
        .asScala
        .headOption
        .map(_.trim)
        .getOrElse(version)

    val read =
      Option
        .when(millVersionPath.isFile)(
          readMillVersion(millVersionPath)
        )
        .orElse(
          Option.when(altMillVersionPath.isFile)(
            readMillVersion(altMillVersionPath)
          )
        )
        .orElse(versionFromBuildfiles)
        .orElse(versionFromYamlFile)
        .orElse(
          if (millPath.isFile)
            Files
              .readAllLines(millPath.toNIO)
              .asScala
              .find(_.startsWith("DEFAULT_MILL_VERSION"))
              .map(line => line.dropWhile(!_.isDigit).trim)
          else None
        )
        .getOrElse(version)

    read
      .stripSuffix("-native")
      .stripSuffix("-jvm")

  }

  private val yamlMillVersionPattern = "mill-version: +([^ ]+) *$".r
  private val yamlHeaderMillVersionPattern = s"//[|] +$yamlMillVersionPattern".r

  private def readMillVersionYaml(
      file: AbsolutePath
  ): Option[String] =
    file match {
      case f if f.isFile =>
        Files
          .readAllLines(f.toNIO)
          .asScala
          .collectFirst { case yamlMillVersionPattern(version) =>
            version
          }
      case _ => None
    }

  private def readMillVersionYamlFrontmatter(
      file: AbsolutePath
  ): Option[String] =
    file match {
      case f if f.isFile =>
        Files
          .readAllLines(f.toNIO)
          .asScala
          .takeWhile(_.startsWith("//|"))
          .collectFirst { case yamlHeaderMillVersionPattern(version) =>
            version
          }
      case _ => None
    }

  override def shouldRegenerateBspJson(
      currentVersion: String,
      workspace: AbsolutePath,
  ): Boolean = {
    currentVersion != getMillVersion(workspace)
  }

  private val predefScriptName = "predef.sc"

  private def embeddedMillWrapper(workspace: AbsolutePath): AbsolutePath = {
    val millWrapper =
      if (Properties.isWin) "mill.bat"
      else "mill"
    val out =
      BuildTool.copyFromResource(
        workspace.resolve(".metals").toNIO,
        millWrapper,
      )
    out.toFile.setExecutable(true)
    AbsolutePath(out)
  }

  override def redirectErrorOutput: Boolean = true

  private def putTogetherArgs(
      cmd: List[String],
      workspace: AbsolutePath,
  ) = {
    // In some environments (such as WSL or cygwin), mill must be run using interactive mode (-i)
    val fullcmd = if (Properties.isWin) "-i" :: cmd else cmd

    userConfig().millScript match {
      case Some(script) => script :: cmd
      case None =>
        if (workspace.resolve("mill.bat").isFile && Properties.isWin)
          workspace.resolve("mill.bat").toString() :: fullcmd
        else if (workspace.resolve("mill").isFile && !Properties.isWin)
          workspace.resolve("mill").toString() :: fullcmd
        else {
          embeddedMillWrapper(workspace).toString() :: fullcmd
        }
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

  override def cleanupStaleConfig(): Unit = {
    // no need to cleanup, the plugin deals with that
  }

  private def bloopCmd(millVersion: String) = {
    // Bloop was moved to the bloop package in 0.9.3
    // https://github.com/com-lihaoyi/mill/pull/992
    val useBloopPackage = SemVer.isCompatibleVersion("0.9.3", millVersion)

    if (useBloopPackage)
      "mill.contrib.bloop.Bloop/install"
    else "mill.contrib.Bloop/install"
  }

  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val millVersion = getMillVersion(projectRoot)
    val cmd =
      bloopImportArgs(millVersion) ::: bloopCmd(millVersion) :: Nil
    putTogetherArgs(cmd, workspace)
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    MillDigest.current(projectRoot)

  override def minimumVersion: String = "0.6.0"

  override def recommendedVersion: String = version

  override def version: String = BuildInfo.millVersion

  override def toString(): String = "Mill"

  override def executableName = MillBuildTool.name

  private def predefScript =
    "import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`".getBytes()

  private def predefScriptPath: Path = {
    Files.write(tempDir.resolve(predefScriptName), predefScript)
  }

  override def createBspFileArgs(
      workspace: AbsolutePath
  ): Option[List[String]] =
    Option.when(workspaceSupportsBsp(workspace: AbsolutePath)) {
      val cmd = "mill.bsp.BSP/install" :: "--jobs" :: "0" :: Nil
      putTogetherArgs(cmd, workspace)
    }

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
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

  override def buildServerName: String = MillBuildTool.bspName

  override def isBloopInstallProvider: Boolean = {
    val millVersion = getMillVersion(projectRoot)
    // 0.14.0 was never released
    !SemVer.isLaterVersion("0.14.0", millVersion)
  }

}

object MillBuildTool {
  val bspName: String = "mill-bsp"
  val name: String = "mill"
  // Mill emits semanticDB in a different way where it's not actually detected in the javac/scalac
  // options like other tools. Therefore for these versions we ensure we don't warn the user that
  // semanticdb isn't being produced and we instead trust Mill to do the job.
  val scalaSemanticDbSupport = "0.10.6"
  val javaSemanticDbSupport = "0.11.0-M2"

  def isMillRelatedPath(
      path: AbsolutePath
  ): Boolean = {
    val filename = path.toNIO.getFileName.toString
    filename.endsWith(".mill") ||
    filename.endsWith(".mill.yaml") ||
    filename.endsWith(".mill.scala") ||
    filename.endsWith(".sc")
  }
}
