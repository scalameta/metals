package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.metals.BuildChangedAction
import scala.meta.internal.metals.Confirmation
import scala.meta.internal.metals.Messages.ImportBuildChanges
import scala.meta.internal.metals.Messages.dontShowAgain
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

/**
 * Class meant to help with the faciliation of reloading the bsp build.
 */
final class WorkspaceReload(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    tables: Tables,
    action: () => BuildChangedAction,
) {

  private val notification = tables.dismissedNotifications.ImportChanges

  def oldReloadResult(digest: String): Option[WorkspaceLoadedStatus] = {
    if (action().isNone) {
      return None
    }
    if (tables.dismissedNotifications.ImportChanges.isDismissed) {
      Some(WorkspaceLoadedStatus.Dismissed)
    } else {
      tables.digests.last().collect {
        case Digest(md5, status, _) if md5 == digest =>
          WorkspaceLoadedStatus.Duplicate(status)
      }
    }
  }

  def persistChecksumStatus(
      status: Status,
      buildTool: BuildTool,
      progress: TaskProgress,
  ): Unit = {
    if (action().isNone) {
      return
    }
    progress.message = s"persisting ${buildTool.toString()} checksum status"
    buildTool.digestWithRetry(workspace).foreach { checksum =>
      tables.digests.setStatus(checksum, status)
    }
  }

  def requestReload(
      buildTool: BuildTool,
      digest: String,
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
    if (action().isNone) {
      // I'm not 100% sure when this gets called, but we don't have to spam the user
      // with a popup if it's triggered regularly behind the scenes. The logs
      // will have this message in case something is behaving unusually.
      scribe.warn(
        "The setting 'buildChangeAction' is set to 'none' meaning that you need to explicitily trigger the 'Import build' command to re-import the build. You won't be prompted to re-import when the build files change."
      )
      return Future.successful(Confirmation.No)
    }
    tables.digests.setStatus(digest, Status.Requested)
    val (params, yes) =
      ImportBuildChanges.params(buildTool.toString) ->
        ImportBuildChanges.yes
    languageClient
      .showMessageRequest(params)
      .asScala
      .map { item =>
        if (item == dontShowAgain) {
          notification.dismissForever()
        }
        Confirmation.fromBoolean(item == yes)
      }
  }
}
