package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.metals.Confirmation
import scala.meta.internal.metals.Messages.ImportBuildChanges
import scala.meta.internal.metals.Messages.dontShowAgain
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

/**
 * Class meant to help with the faciliation of reloading the bsp build.
 */
final class WorkspaceReload(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    tables: Tables,
) {

  private val notification = tables.dismissedNotifications.ImportChanges

  def oldReloadResult(digest: String): Option[WorkspaceLoadedStatus] = {
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
  ): Unit = {
    buildTool.digest(workspace).foreach { checksum =>
      tables.digests.setStatus(checksum, status)
    }
  }

  def requestReload(
      buildTool: BuildTool,
      digest: String,
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
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
