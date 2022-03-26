package scala.meta.internal.builds

import java.nio.file.Files
import java.util.Properties

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

/**
 * Detects what build tool is used in this workspace.
 *
 * Although we only support a limited set of build tools, knowing
 * what build tool is used in the workspace helps to produce better errors
 * for people using unsupported build tools. For example: "Gradle is not supported"
 * instead of "Unsupported build tool".
 *
 * @param bspGlobalDirectories Directories for user and system installed BSP connection
 *                            details according to BSP spec:
 *                            https://build-server-protocol.github.io/docs/server-discovery.html#default-locations-for-bsp-connection-files
 */
final class BuildTools(
    workspace: AbsolutePath,
    bspGlobalDirectories: List[AbsolutePath],
    userConfig: () => UserConfiguration,
    explicitChoiceMade: () => Boolean
) {
  // NOTE: We do a couple extra check here before we say a workspace with a
  // `.bsp` is auto-connectable, and we ensure that a user has explicity chosen
  // to use another build server besides Bloop or it's a BSP server for a build
  // tool we don't support. If this isn't done, it causes unexpected warnings
  // since if a `.bsp/<something>.json` exists before a `.bloop` one does in a
  // workspace with a build tool we support, we will attempt to autoconnect to
  // Bloop since Metals thinks it's in state that's auto-connectable before the
  // user is even prompted.
  def isAutoConnectable: Boolean = {
    isBloop || (isBsp && all.isEmpty) || (isBsp && explicitChoiceMade())
  }
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
  def isGradle: Boolean = {
    val defaultGradlePaths = List(
      "settings.gradle",
      "settings.gradle.kts",
      "build.gradle",
      "build.gradle.kts"
    )
    defaultGradlePaths.exists(workspace.resolve(_).isFile)
  }
  def isMaven: Boolean = workspace.resolve("pom.xml").isFile
  def isPants: Boolean = workspace.resolve("pants.ini").isFile
  def isBazel: Boolean = workspace.resolve("WORKSPACE").isFile

  def allAvailable: List[BuildTool] = {
    List(
      SbtBuildTool(workspaceVersion = None, userConfig),
      GradleBuildTool(userConfig),
      MavenBuildTool(userConfig),
      MillBuildTool(userConfig)
    )
  }

  def all: List[String] = {
    val buf = List.newBuilder[String]
    if (isBloop) buf += BloopServers.name
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

  def loadSupported(): List[BuildTool] = {
    val buf = List.newBuilder[BuildTool]

    if (isSbt) buf += SbtBuildTool(workspace, userConfig)
    if (isGradle) buf += GradleBuildTool(userConfig)
    if (isMaven) buf += MavenBuildTool(userConfig)
    if (isMill) buf += MillBuildTool(userConfig)

    buf.result()
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
    else if (isMill) MillBuildTool.isMillRelatedPath(path)
    else false
  }
}

object BuildTools {
  def default(workspace: AbsolutePath = PathIO.workingDirectory): BuildTools =
    new BuildTools(
      workspace,
      Nil,
      () => UserConfiguration(),
      explicitChoiceMade = () => false
    )
}
