package scala.meta.internal.builds

import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

/**
 * Helper trait for build tools that also impliment bsp
 */
trait BuildServerProvider { this: BuildTool =>

  /**
   * Method used to generate a bsp config file for the build
   * tool if one doesn't exist yet.
   */
  def generateBspConfig(
      workspace: AbsolutePath,
      languageClient: MetalsLanguageClient,
      systemProcess: List[String] => Future[BspConfigGenerationStatus]
  ): Future[BspConfigGenerationStatus] =
    if (workspaceSupportsBsp(workspace)) {
      systemProcess(createBspFileArgs(workspace))
    } else {
      Future.successful(
        Failed(Right(Messages.NoSbtBspSupport.toString()))
      )
    }

  /**
   * Args necessary for build tool to generate the bsp config file.
   */
  def createBspFileArgs(workspace: AbsolutePath): List[String]

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean
}
