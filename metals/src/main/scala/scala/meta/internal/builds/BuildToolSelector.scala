package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Messages.ChooseBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.Tables

import org.eclipse.lsp4j.MessageActionItem

/**
 * Helper class to find a previously selected build tool or to facilitate the
 *     choosing of the tool.
 */
final class BuildToolSelector(
    languageClient: MetalsLanguageClient,
    tables: Tables
)(implicit ec: ExecutionContext) {
  def checkForChosenBuildTool(
      buildTools: List[BuildTool]
  ): Future[Option[BuildTool]] =
    tables.buildTool.selectedBuildTool match {
      case Some(chosen) =>
        Future(buildTools.find(_.executableName == chosen))
      case None => requestBuildToolChoice(buildTools)
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
}
