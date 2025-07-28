package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.ChooseBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageActionItem

/**
 * Helper class to find a previously selected build tool or to facilitate the
 *     choosing of the tool.
 */
final class BuildToolSelector(
    languageClient: MetalsLanguageClient,
    tables: Tables,
    preferredBuildServer: Future[Option[String]],
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
            preferredBuildServer
              .map(p =>
                p.flatMap(name => buildTools.find(_.buildServerName == name))
              )
              .flatMap {
                case Some(bt) => Future.successful(Some(bt))
                case None => requestBuildToolChoice(buildTools)
              }
        }
    }

  private def requestBuildToolChoice(
      buildTools: List[BuildTool]
  ): Future[Option[BuildTool]] = {
    languageClient
      .showMessageRequest(ChooseBuildTool.params(buildTools))
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
          .params(newBuildTool.executableName, currentBuildTool.executableName)
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
