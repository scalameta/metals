package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.ChooseBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageActionItem

/**
 * Helper class to find a previously selected build tool or to facilitate the
 *     choosing of the tool.
 */
final class BuildToolSelector(
    languageClient: MetalsLanguageClient,
    tables: Tables,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext) {
  def checkForChosenBuildTool(
      buildTools: List[BuildTool]
  ): Future[Option[BuildTool]] =
    tables.buildTool.selectedBuildTool() match {
      case Some(chosen) if buildTools.exists(_.executableName == chosen) =>
        Future(buildTools.find(_.executableName == chosen))
      case _ =>
        buildTools match {
          case buildTool :: Nil =>
            tables.buildTool.chooseBuildTool(buildTool.executableName)
            Future.successful(Some(buildTool))
          case _ =>
            // Check if user has configured a preferred build tool
            userConfig().targetBuildTool match {
              case Some(preferredTool) =>
                buildTools.find(_.executableName == preferredTool) match {
                  case Some(buildTool) =>
                    tables.buildTool.chooseBuildTool(buildTool.executableName)
                    Future.successful(Some(buildTool))
                  case None =>
                    scribe.warn(
                      s"Configured target-build-tool '$preferredTool' not found in available build tools: ${buildTools.map(_.executableName).mkString(", ")}"
                    )
                    requestBuildToolChoice(buildTools)
                }
              case None =>
                requestBuildToolChoice(buildTools)
            }
        }
    }

  private def requestBuildToolChoice(
      buildTools: List[BuildTool]
  ): Future[Option[BuildTool]] = {
    languageClient
      .showMessageRequest(
        ChooseBuildTool.params(buildTools),
        defaultTo = () => {
          val tool = userConfig().targetBuildTool
            .flatMap { tool =>
              buildTools.find(_.executableName == tool)
            }
            .orElse(buildTools.headOption)
            .getOrElse {
              throw new IllegalStateException("No build tool found")
            }
          languageClient.showMessage(ChooseBuildTool.notificationParams(tool))
          new MessageActionItem(tool.executableName)
        },
      )
      .asScala
      .map { choice =>
        val foundBuildTool = buildTools.find(buildTool =>
          new MessageActionItem(buildTool.executableName) == choice
        )
        foundBuildTool.foreach(buildTool =>
          tables.buildTool.chooseBuildTool(buildTool.executableName)
        )
        foundBuildTool
      }
  }

  def onNewBuildToolAdded(
      newBuildTool: BuildTool,
      currentBuildTool: BuildTool,
  ): Future[Boolean] = {
    languageClient
      .showMessageRequest(
        Messages.NewBuildToolDetected
          .params(newBuildTool.executableName, currentBuildTool.executableName),
        defaultTo = () => {
          languageClient.showMessage(
            Messages.NewBuildToolDetected.notificationParams(
              newBuildTool.executableName
            )
          )
          Messages.NewBuildToolDetected.dontSwitch
        },
      )
      .asScala
      .map {
        case Messages.NewBuildToolDetected.switch =>
          tables.buildServers.reset()
          tables.buildTool.chooseBuildTool(newBuildTool.executableName)
          true
        case _ => false
      }
  }
}
