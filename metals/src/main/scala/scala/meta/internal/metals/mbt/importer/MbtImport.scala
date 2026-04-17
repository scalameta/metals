package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success
import scala.util.Try

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.builds.WorkspaceLoadedStatus
import scala.meta.internal.metals.Confirmation
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.io.AbsolutePath

/**
 * Manages the lifecycle of MBT importers.
 */
final class MbtImport(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    tables: Tables,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext) {

  private lazy val notification = tables.dismissedNotifications.MbtImportChanges

  /**
   * Calls [[MbtImportProvider.extract]] on every provider, reads their output
   * files, merges the results and writes `.metals/mbt.json`.
   */
  def runUnconditionally(
      providers: List[MbtImportProvider],
      isImportInProcess: AtomicBoolean,
  ): Future[WorkspaceLoadedStatus] = {
    if (isImportInProcess.compareAndSet(false, true)) {
      Future
        .sequence(
          providers.map(p =>
            p.extract(workspace)
              .map { _ =>
                Try(MbtBuild.fromFile(p.outputPath(workspace).toNIO)).toOption
              }
              .recover { case ex =>
                scribe.error(s"mbt-import: provider '${p.name}' failed", ex)
                None
              }
          )
        )
        .map { results =>
          val builds = results.flatten
          val merged = builds.foldLeft(MbtBuild.empty)(MbtBuild.merge)
          writeOutput(merged)
          if (builds.isEmpty && providers.nonEmpty)
            WorkspaceLoadedStatus.Failed(-1)
          else
            WorkspaceLoadedStatus.Installed
        }
        .recover { case ex =>
          scribe.error("mbt-import: unexpected error during extraction", ex)
          WorkspaceLoadedStatus.Failed(-1)
        }
        .andThen { _ => isImportInProcess.set(false) }
    } else {
      Future.successful {
        languageClient.showMessage(Messages.ImportAlreadyRunning)
        WorkspaceLoadedStatus.Dismissed
      }
    }
  }

  /**
   * Serializes a merged [[MbtBuild]] to `.metals/mbt.json`.
   */
  private def writeOutput(build: MbtBuild): Unit = {
    val metalsDir = workspace.resolve(".metals")
    Files.createDirectories(metalsDir.toNIO)
    val outputFile = metalsDir.resolve("mbt.json")
    Files.writeString(outputFile.toNIO, MbtBuild.toJson(build))
    scribe.info("mbt-import: wrote .metals/mbt.json")
  }

  /**
   * Like [[runUnconditionally]] but first checks whether the build digest has
   * changed and prompts the user for confirmation when auto-import is disabled.
   */
  def runIfApproved(
      providers: List[MbtImportProvider],
      isImportInProcess: AtomicBoolean,
  ): Future[WorkspaceLoadedStatus] = {
    computeDigest(providers) match {
      case None =>
        scribe.debug(
          "mbt-import: no digest available, running unconditionally"
        )
        runUnconditionally(providers, isImportInProcess)
      case Some(digest) =>
        oldImportResult(digest) match {
          case Some(result @ WorkspaceLoadedStatus.Duplicate(s))
              if s == Status.Installed || s == Status.Rejected =>
            scribe.info(
              s"mbt-import: skipping import with status '${result.name}'"
            )
            Future.successful(result)
          case _ =>
            val run =
              if (userConfig().shouldAutoImportNewProject) {
                runUnconditionally(providers, isImportInProcess)
              } else {
                scribe.debug("mbt-import: awaiting user response…")
                for {
                  response <- requestImport(providers, digest)
                  result <-
                    if (response.isYes)
                      runUnconditionally(providers, isImportInProcess)
                    else {
                      notification.dismiss(2, TimeUnit.MINUTES)
                      Future.successful(WorkspaceLoadedStatus.Rejected)
                    }
                } yield result
              }
            run.andThen { case Success(status) =>
              status.toChecksumStatus.foreach(
                tables.digests.setStatus(digest, _)
              )
            }
        }
    }
  }

  private def computeDigest(
      providers: List[MbtImportProvider]
  ): Option[String] = {
    val parts = providers.map(_.digest(workspace))
    if (parts.isEmpty || parts.exists(_.isEmpty)) None
    else Some(parts.flatten.mkString("|"))
  }

  private def oldImportResult(
      digest: String
  ): Option[WorkspaceLoadedStatus] = {
    if (notification.isDismissed) {
      Some(WorkspaceLoadedStatus.Dismissed)
    } else {
      tables.digests.last().collect {
        case d if d.md5 == digest =>
          WorkspaceLoadedStatus.Duplicate(d.status)
      }
    }
  }

  private def requestImport(
      providers: List[MbtImportProvider],
      digest: String,
  ): Future[Confirmation] = {
    tables.digests.setStatus(digest, Status.Requested)
    val displayName = s"MBT (${providers.map(_.name).mkString(", ")})"
    val hasMbtJson = workspace.resolve(".metals/mbt.json").isFile
    val (params, yes) =
      if (hasMbtJson)
        Messages.ImportBuildChanges.params(displayName) ->
          Messages.ImportBuildChanges.yes
      else
        Messages.ImportBuild.params(displayName) ->
          Messages.ImportBuild.yes

    languageClient
      .showMessageRequest(params)
      .asScala
      .map { item =>
        if (item == Messages.dontShowAgain) notification.dismissForever()
        Confirmation.fromBoolean(item == yes)
      }
  }
}
