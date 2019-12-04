package scala.meta.internal.builds

import java.nio.file.Files
import java.util.Properties
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.io.PathIO
import scala.concurrent.ExecutionContext

/**
 * Detects what build tool is used in this workspace.
 *
 * Although we only support a limited set of build tools, knowing
 * what build tool is used in the workspace helps to produce better error
 * for people using unsupported build tools. For example: "Gradle is not supported"
 * instead of "Unsupported build tool".
 *
 * @param bspGlobalDirectories Directories for user and system installed BSP connection
 *                            details according to BSP spec:
 *                            https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#default-locations-for-bsp-connection-files
 */
final class BuildTools(
    workspace: AbsolutePath,
    bspGlobalDirectories: List[AbsolutePath],
    userConfig: () => UserConfiguration,
    config: MetalsServerConfig
)(implicit ec: ExecutionContext) {
  def isAutoConnectable: Boolean =
    isBloop || isBsp
  def isBloop: Boolean = {
    hasJsonFile(workspace.resolve(".bloop"))
  }
  def isBsp: Boolean = {
    hasJsonFile(workspace.resolve(".bsp")) ||
    bspGlobalDirectories.exists(hasJsonFile)
  }
  private def hasJsonFile(dir: AbsolutePath): Boolean = {
    dir.list.exists(_.extension == "json")
  }
  // Returns true if there's a build.sbt file or project/build.properties with sbt.version
  def isSbt: Boolean = {
    workspace.resolve("build.sbt").isFile || {
      val buildProperties =
        workspace.resolve("project").resolve("build.properties")
      buildProperties.isFile && {
        val props = new Properties()
        val in = Files.newInputStream(buildProperties.toNIO)
        try props.load(in)
        finally in.close()
        props.getProperty("sbt.version") != null
      }
    }
  }
  def isMill: Boolean = workspace.resolve("build.sc").isFile
  def isGradle: Boolean =
    workspace.resolve("build.gradle").isFile || workspace
      .resolve("build.gradle.kts")
      .isFile
  def isMaven: Boolean = workspace.resolve("pom.xml").isFile
  def isPants: Boolean = workspace.resolve("pants.ini").isFile
  def isBazel: Boolean = workspace.resolve("WORKSPACE").isFile

  def allAvailable: List[BuildTool] = {
    List(
      SbtBuildTool("", userConfig, config),
      GradleBuildTool(userConfig),
      MavenBuildTool(userConfig),
      MillBuildTool(userConfig)
    )
  }

  def all: List[String] = {
    val buf = List.newBuilder[String]
    if (isBloop) buf += "Bloop"
    if (isSbt) buf += "sbt"
    if (isMill) buf += "Mill"
    if (isGradle) buf += "Gradle"
    if (isMaven) buf += "Maven"
    if (isPants) buf += "Pants"
    if (isBazel) buf += "Bazel"
    buf.result()
  }
  def isEmpty: Boolean = {
    all.isEmpty
  }
  def loadSupported(): Option[BuildTool] = {
    if (isSbt) Some(SbtBuildTool(workspace, userConfig, config))
    else if (isGradle) Some(GradleBuildTool(userConfig))
    else if (isMaven) Some(MavenBuildTool(userConfig))
    else if (isMill) Some(MillBuildTool(userConfig))
    else None
  }

  override def toString: String = {
    val names = all.mkString("+")
    if (names.isEmpty) "<no build tool>"
    else names
  }
  def isBuildRelated(workspace: AbsolutePath, path: AbsolutePath): Boolean = {
    if (isSbt) SbtBuildTool.isSbtRelatedPath(workspace, path)
    else if (isGradle) GradleBuildTool.isGradleRelatedPath(workspace, path)
    else if (isMaven) MavenBuildTool.isMavenRelatedPath(workspace, path)
    else if (isMill) MillBuildTool.isMillRelatedPath(workspace, path)
    else false
  }
}

object BuildTools {
  def default(workspace: AbsolutePath = PathIO.workingDirectory): BuildTools =
    new BuildTools(
      workspace,
      Nil,
      () => UserConfiguration(),
      MetalsServerConfig.default
    )(ExecutionContext.global)
}
