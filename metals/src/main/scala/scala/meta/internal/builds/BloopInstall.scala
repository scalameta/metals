package scala.meta.internal.builds

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Confirmation
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

/**
 * Runs `sbt/gradle/mill/mvn bloopInstall` processes.
 *
 * Handles responsibilities like:
 * - install metals
 * - launching embedded build tool via system process
 * - reporting client about `bloopInstall` progress
 */
final class BloopInstall(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    buildTools: BuildTools,
    tables: Tables,
    shellRunner: ShellRunner,
)(implicit ec: ExecutionContext) {

  override def toString: String = s"BloopInstall($workspace)"

  def runUnconditionally(
      buildTool: BuildTool,
      isImportInProcess: AtomicBoolean,
  ): Future[WorkspaceLoadedStatus] = {
    if (isImportInProcess.compareAndSet(false, true)) {
      buildTool.bloopInstall(
        workspace,
        args => {
          scribe.info(s"running '${args.mkString(" ")}'")
          val process = runArgumentsUnconditionally(buildTool, args)
          process.foreach { e =>
            if (e.isFailed) {
              // Record the exact command that failed to help troubleshooting.
              scribe.error(s"$buildTool command failed: ${args.mkString(" ")}")
            }
          }
          process.onComplete(_ => isImportInProcess.set(false))
          process
        },
      )
    } else {
      Future
        .successful {
          languageClient.showMessage(ImportAlreadyRunning)
          WorkspaceLoadedStatus.Dismissed
        }
    }
  }

  private def runArgumentsUnconditionally(
      buildTool: BuildTool,
      args: List[String],
  ): Future[WorkspaceLoadedStatus] = {
    persistChecksumStatus(Status.Started, buildTool)
    val processFuture = shellRunner
      .run(
        s"${buildTool.executableName} bloopInstall",
        args,
        workspace,
        buildTool.redirectErrorOutput,
        Map(
          "COURSIER_PROGRESS" -> "disable",
          // Envs below might be used to customize build/bloopInstall procedure.
          // Example: you can disable `Xfatal-warnings` scalac option only for Metals.
          "METALS_ENABLED" -> "true",
          "SCALAMETA_VERSION" -> BuildInfo.semanticdbVersion,
        ) ++ sys.env,
      )
      .map {
        case ExitCodes.Success => WorkspaceLoadedStatus.Installed
        case ExitCodes.Cancel => WorkspaceLoadedStatus.Cancelled
        case result => WorkspaceLoadedStatus.Failed(result)
      }
    processFuture.foreach { result =>
      try result.toChecksumStatus.foreach(persistChecksumStatus(_, buildTool))
      catch {
        case _: InterruptedException =>
      }
    }
    processFuture
  }

  private val notification = tables.dismissedNotifications.ImportChanges

  private def oldInstallResult(
      digest: String
  ): Option[WorkspaceLoadedStatus] = {
    if (notification.isDismissed) {
      Some(WorkspaceLoadedStatus.Dismissed)
    } else {
      tables.digests.last().collect {
        case Digest(md5, status, _) if md5 == digest =>
          WorkspaceLoadedStatus.Duplicate(status)
      }
    }
  }

  // NOTE(olafur) there's a chance that we get two build change notifications in
  // a very short period due to duplicate `didSave` and file watching
  // notifications. This method is synchronized to prevent asking the user
  // twice whether to import the build.
  def runIfApproved(
      buildTool: BuildTool,
      digest: String,
      isImportInProcess: AtomicBoolean,
  ): Future[WorkspaceLoadedStatus] =
    synchronized {
      oldInstallResult(digest) match {
        case Some(result)
            if result != WorkspaceLoadedStatus.Duplicate(Status.Requested) =>
          scribe.info(s"skipping build import with status '${result.name}'")
          Future.successful(result)
        case _ =>
          for {
            userResponse <- requestImport(
              buildTools,
              buildTool,
              languageClient,
              digest,
            )
            installResult <- {
              if (userResponse.isYes) {
                runUnconditionally(buildTool, isImportInProcess)
              } else {
                // Don't spam the user with requests during rapid build changes.
                notification.dismiss(2, TimeUnit.MINUTES)
                Future.successful(WorkspaceLoadedStatus.Rejected)
              }
            }
          } yield installResult
      }
    }

  private def persistChecksumStatus(
      status: Status,
      buildTool: BuildTool,
  ): Unit = {
    buildTool.digest(workspace).foreach { checksum =>
      tables.digests.setStatus(checksum, status)
    }
  }

  private def requestImport(
      buildTools: BuildTools,
      buildTool: BuildTool,
      languageClient: MetalsLanguageClient,
      digest: String,
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
    tables.digests.setStatus(digest, Status.Requested)
    val (params, yes) =
      if (buildTools.isBloop) {
        ImportBuildChanges.params(buildTool.toString) ->
          ImportBuildChanges.yes
      } else {
        ImportBuild.params(buildTool.toString) ->
          ImportBuild.yes
      }
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
