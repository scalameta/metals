package scala.meta.internal.builds

import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

/**
 * Alternative bsp for Bazel (Mezel).
 */
case class MezelBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BuildServerProvider
    with VersionRecommendation {

  override def digest(workspace: AbsolutePath): Option[String] = {
    BazelDigest.current(projectRoot)
  }

  override def generateBspConfig(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[
        BspConfigGenerationStatus.BspConfigGenerationStatus
      ],
      statusBar: StatusBar,
  ): Future[BspConfigGenerationStatus.BspConfigGenerationStatus] = {
    val bspConfig =
      workspace
        .resolve(Directories.bsp)
        .resolve(s"${MezelBuildTool.name.toLowerCase()}.json")
    statusBar.addMessage("mezel bspConfig")
    bspConfig.writeText(
      """|{"argv":[
         |  "bazel", "run", "@mezel//rules:mezel_jar", "--"
         |],"bspVersion":"2.1.0","languages":["scala"],"name":"Mezel","version":"1.0.0"}
         |""".stripMargin
    )
    Future.successful(BspConfigGenerationStatus.Generated)
  }

  def createBspFileArgs(workspace: AbsolutePath): Option[List[String]] = None

  def workspaceSupportsBsp: Boolean = {
    projectRoot.list.exists {
      case file if file.filename == "WORKSPACE" => true
      case _ => false
    }
  }

  override def minimumVersion: String = "0.2.0"

  override def recommendedVersion: String = "0.2.11"

  override def version: String = "0.2.11"

  override def toString: String = MezelBuildTool.name

  override def executableName = MezelBuildTool.name

}

object MezelBuildTool {
  val name = "Mezel"
}
