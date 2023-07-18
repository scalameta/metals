package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.io.AbsolutePath

class ScalaCliBuildTool(val workspaceVersion: Option[String])
    extends BuildTool {

  override def digest(workspace: AbsolutePath): Option[String] =
    ScalaCliDigest.current(workspace)

  override def minimumVersion: String = ScalaCli.minVersion

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)

  override def recommendedVersion: String = BuildInfo.scalaCliVersion

  override def executableName: String = ScalaCliBuildTool.name

  override def isBloopDefaultBsp = false

}

object ScalaCliBuildTool {
  def name = "scala-cli"
  def pathsToScalaCliBsp(root: AbsolutePath): List[AbsolutePath] = List(
    root.resolve(".bsp").resolve("scala-cli.json"),
    root.resolve(".bsp").resolve("scala.json"),
  )

  def apply(root: AbsolutePath): ScalaCliBuildTool = {
    val workspaceFolderVersions =
      for {
        path <- pathsToScalaCliBsp(root)
        text <- path.readTextOpt
        json = ujson.read(text)
        version <- json("version").strOpt
      } yield version
    new ScalaCliBuildTool(workspaceFolderVersions.headOption)
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
