package scala.meta.internal.builds

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.GsonBuilder

/**
 * Registers the Bazel Native integration as a build tool within Metals.
 * Uses the naming convention "bazel-native" to stay distinct from the
 * existing JetBrains Bazel BSP integration ("bazelbsp").
 */
case class BazelNativeBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BuildServerProvider {

  override def digest(workspace: AbsolutePath): Option[String] =
    BazelDigest.current(projectRoot)

  override def createBspFileArgs(
      workspace: AbsolutePath
  ): Option[List[String]] = None

  override def toString: String = BazelNativeBuildTool.name

  override def executableName: String = BazelNativeBuildTool.name

  override val forcesBuildServer: Boolean = true

  override def buildServerName: String = BazelNativeBuildTool.bspName

  override def shouldRegenerateBspJson(
      currentVersion: String
  ): Boolean = false

  override def generateBspConfig(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[BspConfigGenerationStatus],
      statusBar: StatusBar,
  ): Future[BspConfigGenerationStatus] = {
    writeSyntheticBspConfig(workspace)
    Future.successful(Generated)
  }

  override def ensurePrerequisites(workspace: AbsolutePath): Unit = ()

  private def writeSyntheticBspConfig(workspace: AbsolutePath): Unit = {
    val bspDir = workspace.resolve(".bsp")
    Files.createDirectories(bspDir.toNIO)
    val configFile = bspDir.resolve(s"${BazelNativeBuildTool.bspName}.json")

    val connectionDetails = new BspConnectionDetails(
      BazelNativeBuildTool.bspName,
      java.util.Collections.emptyList(),
      BazelBuildTool.version,
      "2.1.0",
      java.util.List.of("scala", "java"),
    )
    val gson = new GsonBuilder().setPrettyPrinting().create()
    val json = gson.toJson(connectionDetails)
    Files.write(configFile.toNIO, json.getBytes(StandardCharsets.UTF_8))
  }
}

object BazelNativeBuildTool {
  val name: String = "bazel-native"
  val bspName: String = "bazel-native"

  def isBazelNativeRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean =
    BazelBuildTool.isBazelRelatedPath(workspace, path)
}
