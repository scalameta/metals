package scala.meta.internal.builds

import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.metals.Messages
import scala.meta.io.AbsolutePath

/**
 * Helper trait for build tools that also impliment bsp
 */
trait BuildServerProvider extends BuildTool {

  /**
   * Method used to generate a bsp config file for the build
   * tool if one doesn't exist yet.
   */
  def generateBspConfig(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[BspConfigGenerationStatus]
  ): Future[BspConfigGenerationStatus] =
    if (workspaceSupportsBsp(workspace)) {
      systemProcess(createBspFileArgs(workspace))
    } else {
      Future.successful(
        Failed(Right(Messages.NoBspSupport.toString()))
      )
    }

  /**
   * Args necessary for build tool to generate the bsp config file.
   */
  def createBspFileArgs(workspace: AbsolutePath): List[String]

  /**
   * Whether or not the build tool workspace supports BSP. Many times this is
   * limited by the version of the build tool that introduces BSP support.
   */
  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean

  /**
   * Name of the build server if different than the actual build-tool that is
   * serving as a build server.
   *
   * Ex. mill isn't mill, but rather mill-bsp
   */
  def buildServerName: Option[String] = None

  def getBuildServerName: String = buildServerName.getOrElse(executableName)
}
