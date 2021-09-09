package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Messages.DisplayBuildTarget
import org.eclipse.lsp4j.ExecuteCommandParams

final class BuildTargetProvider(
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient
)(implicit ec: ExecutionContext) {

  private def toQuickPickItem(target: CommonTarget): MetalsQuickPickItem = {
    MetalsQuickPickItem(target.displayName, target.displayName)
  }

  def askForBuildTargetAndDisplayBuildTargetInfo: Future[Unit] = {
    askForBuildTarget.flatMap(f =>
      f.map(displayBuildTargetInfo).getOrElse(Future.successful(Unit))
    )
  }

  def displayBuildTargetInfo(param: String): Future[Unit] = {
    Future {
      // handle param as buildTarget name or "projects:file/..../id=buildTargetName!..." url
      val buildTarget = if (param.startsWith("projects:")) {
        val start = param.lastIndexOf("/?id=")
        if (start < 0) ""
        else {
          val end = param.indexOf("!/", start)
          if (end < 0) ""
          else
            param.substring(start + 5, end)
        }
      } else param
      val command = new ExecuteCommandParams(
        ClientCommands.TargetInfoDisplay.id,
        List[Object](
          new BuildTargetInfo(buildTargets).buildTargetDetailsHtml(buildTarget)
        ).asJava
      )
      languageClient.metalsExecuteClientCommand(command)
    }
  }

  private def askForBuildTarget: Future[Option[String]] = {
    val allCommon = buildTargets.allCommon
    if (allCommon.hasNext) {
      val quickPicks = allCommon.map(toQuickPickItem).toList.sortBy(_.id)
      languageClient
        .metalsQuickPick(
          MetalsQuickPickParams(
            quickPicks.asJava,
            placeHolder = DisplayBuildTarget.selectTheBuildTargetMessage
          )
        )
        .asScala
        .map {
          case target if !target.cancelled => Option(target.itemId)
          case _ => None
        }
    } else {
      Future.successful(None)
    }
  }
}
