package scala.meta.internal.builds

import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.StatusBar
import scala.meta.io.AbsolutePath

/**
 * Helper trait for build tools that also implement bsp
 */
trait BuildServerProvider extends BuildTool {

  /**
   * Method used to generate a bsp config file for the build
   * tool if one doesn't exist yet.
   */
  def generateBspConfig(
      workspace: AbsolutePath,
      systemProcess: List[String] => Future[BspConfigGenerationStatus],
      statusBar: StatusBar,
  ): Future[BspConfigGenerationStatus] =
    createBspFileArgs(workspace).map(systemProcess).getOrElse {
      Future.successful(
        Failed(Right(Messages.NoBspSupport.toString()))
      )
    }

  /**
   * Args necessary for build tool to generate the bsp config file
   * if the build tool workspace supports BSP. Many times this is
   * limited by the version of the build tool that introduces BSP support.
   */
  protected def createBspFileArgs(workspace: AbsolutePath): Option[List[String]]

}
