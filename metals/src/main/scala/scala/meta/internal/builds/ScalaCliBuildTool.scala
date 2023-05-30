package scala.meta.internal.builds

import java.security.MessageDigest

import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonParser

class ScalaCliBuildTool(val workspaceVersion: Option[String])
    extends BuildTool {

  override def bloopInstall(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[WorkspaceLoadedStatus],
  ): Future[WorkspaceLoadedStatus] =
    Future.successful(WorkspaceLoadedStatus.Dismissed)

  override def digest(workspace: AbsolutePath): Option[String] =
    ScalaCliDigest.current(workspace)

  override def minimumVersion: String = "0.1.4"

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)

  override def recommendedVersion: String = BuildInfo.scalaCliVersion

  override def executableName: String = ScalaCliBuildTool.name

  override def isBloopDefaultBsp = false

}

object ScalaCliBuildTool {
  def name = "scala-cli"

  def pathToScalaCliBsp(root: AbsolutePath): AbsolutePath =
    root.resolve(".bsp").resolve("scala-cli.json")

  def apply(root: AbsolutePath): ScalaCliBuildTool = {
    val workspaceFolder =
      Try(
        JsonParser
          .parseString(pathToScalaCliBsp(root).readText)
          .getAsJsonObject()
          .get("version")
          .getAsString()
      ).toOption
    new ScalaCliBuildTool(workspaceFolder)
  }
}

object ScalaCliDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    Digest.digestFileBytes(
      ScalaCliBuildTool.pathToScalaCliBsp(workspace),
      digest,
    )
  }
}
