package scala.meta.internal.builds

import java.security.MessageDigest

import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.io.AbsolutePath

class ScalaCliBuildTool(
    val workspaceVersion: Option[String],
    val projectRoot: AbsolutePath,
    userConfig: () => UserConfiguration,
) extends BuildTool
    with BuildServerProvider
    with VersionRecommendation {

  lazy val runScalaCliCommand: Option[Seq[String]] =
    ScalaCli.localScalaCli(userConfig())

  override def generateBspConfig(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[BspConfigGenerationStatus],
      statusBar: StatusBar,
  ): Future[BspConfigGenerationStatus] =
    createBspFileArgs(workspace).map(systemProcess).getOrElse {
      // fallback to creating `.bsp/scala-cli.json` that starts JVM launcher
      val bspConfig = workspace.resolve(".bsp").resolve("scala-cli.json")
      statusBar.addMessage("scala-cli bspConfig")
      bspConfig.writeText(
        ScalaCli.scalaCliBspJsonContent(projectRoot = projectRoot.toString())
      )
      Future.successful(Generated)
    }

  override def createBspFileArgs(
      workspace: AbsolutePath
  ): Option[List[String]] =
    runScalaCliCommand.map(
      _.toList ++ List(
        "setup-ide",
        projectRoot.toString(),
      )
    )

  override def digest(workspace: AbsolutePath): Option[String] =
    ScalaCliDigest.current(workspace)

  override def minimumVersion: String = ScalaCli.minVersion

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)

  override def recommendedVersion: String = BuildInfo.scalaCliVersion

  override def executableName: String = ScalaCliBuildTool.name

  override val forcesBuildServer = true

  def isBspGenerated(workspace: AbsolutePath): Boolean =
    ScalaCliBuildTool.pathsToScalaCliBsp(workspace).exists(_.isFile)

}

object ScalaCliBuildTool {
  def name = "scala-cli"
  def pathsToScalaCliBsp(root: AbsolutePath): List[AbsolutePath] =
    ScalaCli.names.toList.map(name =>
      root.resolve(".bsp").resolve(s"$name.json")
    )

  def apply(
      workspace: AbsolutePath,
      projectRoot: AbsolutePath,
      userConfig: () => UserConfiguration,
  ): ScalaCliBuildTool = {
    val workspaceFolderVersions =
      for {
        path <- pathsToScalaCliBsp(workspace)
        text <- path.readTextOpt
        json = ujson.read(text)
        version <- json("version").strOpt
      } yield version
    new ScalaCliBuildTool(
      workspaceFolderVersions.headOption,
      projectRoot,
      userConfig,
    )
  }
}

object ScalaCliDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    ScalaCliBuildTool
      .pathsToScalaCliBsp(workspace)
      .foldRight(true)((path, acc) =>
        acc && Digest.digestFileBytes(path, digest)
      )
  }
}
