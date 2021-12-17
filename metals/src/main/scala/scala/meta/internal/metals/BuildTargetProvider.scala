package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Messages.DisplayBuildTarget
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import ch.epfl.scala.bsp4j.BuildTarget

final class BuildTargetProvider(
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient
)(implicit ec: ExecutionContext) {

  private def toQuickPickItem(target: BuildTarget): MetalsQuickPickItem = {
    MetalsQuickPickItem(target.getDisplayName(), target.getDisplayName())
  }

  def askForBuildTargetAndDisplayBuildTargetInfo(
      workspace: AbsolutePath
  ): Future[Unit] = {
    askForBuildTarget.flatMap(f =>
      f.map(param => displayBuildTargetInfo(workspace, param))
        .getOrElse(Future.successful(Unit))
    )
  }

  def displayBuildTargetInfo(
      workspace: AbsolutePath,
      param: String
  ): Future[Unit] = {
    Future {
      // handle param as buildTarget name or "projects:file/..../id=buildTargetName!..." url from MetalsTreeView
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
      val buildTargetURI =
        workspace.resolve(s"$buildTarget.metals-buildtarget").toURI.toString
      val metalsDecodeURI = s"metalsDecode:$buildTargetURI"
      val position = new Position(0, 0)
      val cursorRange = new Range(position, position)
      val windowsLocation =
        new ClientCommands.WindowLocation(metalsDecodeURI, cursorRange, false)
      languageClient.metalsExecuteClientCommand(
        ClientCommands.GotoLocation.toExecuteCommandParams(windowsLocation)
      )
    }
  }

  private def askForBuildTarget: Future[Option[String]] = {
    val allCommon = buildTargets.all
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
        .map(_.map(_.itemId))
    } else {
      Future.successful(None)
    }
  }
}
