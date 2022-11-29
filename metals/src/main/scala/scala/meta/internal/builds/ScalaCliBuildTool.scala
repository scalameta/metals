package scala.meta.internal.builds

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

final case class ScalaCliBuildTool(userConfig: () => UserConfiguration)
    extends BuildServerProvider {
  def digest(workspace: AbsolutePath): Option[String] =
    ScalaCliDigest.current(workspace)

  def version: String = BuildInfo.scalaCliVersion

  def minimumVersion: String = "0.1.9"

  def recommendedVersion: String = version

  override def toString: String = "ScalaCli"

  def executableName: String = "scala-cli"

  def createBspFileArgs(workspace: AbsolutePath): List[String] = List(
    userConfig().scalaCliLauncher.getOrElse(executableName),
    "setup-ide",
    workspace.toString,
  )

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = true

  override def buildServerName: Option[String] = None
}
