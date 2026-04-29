package scala.meta.internal.builds

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

case class DederBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BuildServerProvider {

  override def createBspFileArgs(
      workspace: AbsolutePath
  ): Option[List[String]] =
    Option.when(workspace.resolve(DederBuildTool.buildFile).isFile)(
      List(DederBuildTool.name, "bsp", "install")
    )

  override def digest(workspace: AbsolutePath): Option[String] =
    DederDigest.current(projectRoot)

  override def executableName: String = DederBuildTool.name

  override def toString: String = "Deder"
}

object DederBuildTool {
  val name: String = "deder"
  val buildFile: String = "deder.pkl"

  def isDederRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean =
    path.toNIO.startsWith(workspace.toNIO) && path.filename == buildFile
}
