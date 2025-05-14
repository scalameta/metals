package scala.meta.internal.builds

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.CancelSwitch
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.Confirmation
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.Interruptable
import scala.meta.internal.metals.Interruptable._
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.parsing.BloopDiagnosticsParser
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
    userConfig: () => UserConfiguration,
    diagnostics: Diagnostics,
)(implicit ec: ExecutionContext) {

  override def toString: String = s"BloopInstall($workspace)"

  def runUnconditionally(
      buildTool: BloopInstallProvider
  ): CancelableFuture[WorkspaceLoadedStatus] = {
    buildTool.bloopInstall(
      workspace,
      args => {
        scribe.info(s"running '${args.mkString(" ")}'")
        val process =
          runArgumentsUnconditionally(buildTool, args, userConfig().javaHome)
        process.future.foreach { e =>
          if (e.isFailed) {
            // Record the exact command that failed to help troubleshooting.
            scribe.error(s"$buildTool command failed: ${args.mkString(" ")}")
          }
        }
        process
      },
    )
  }

  private def runArgumentsUnconditionally(
      buildTool: BloopInstallProvider,
      args: List[String],
      javaHome: Option[String],
  ): CancelableFuture[WorkspaceLoadedStatus] = {
    persistChecksumStatus(Status.Started, buildTool)
    val buffer = scala.collection.mutable.ArrayBuffer.empty[String]
    val errorHandler = (x: String) => {
      buffer.append(x)
      scribe.error(x)
    }

    val processFuture = shellRunner
      .run(
        s"${buildTool.executableName} bloopInstall",
        args,
        buildTool.projectRoot,
        buildTool.redirectErrorOutput,
        javaHome,
        Map(
          "COURSIER_PROGRESS" -> "disable",
          // Envs below might be used to customize build/bloopInstall procedure.
          // Example: you can disable `Xfatal-warnings` scalac option only for Metals.
          "METALS_ENABLED" -> "true",
          "SCALAMETA_VERSION" -> BuildInfo.semanticdbVersion,
        ) ++ sys.env,
        processErr = errorHandler,
      )
      .map {
        case ExitCodes.Success => WorkspaceLoadedStatus.Installed
        case ExitCodes.Cancel => WorkspaceLoadedStatus.Cancelled
        case result => WorkspaceLoadedStatus.Failed(result)
      }
      .map { value =>
        BloopDiagnosticsParser
          .getDiagnosticsFromErrors(buffer.toArray)
          .foreach(diagnostic =>
            diagnostics.onPublishDiagnostics(
              diagnostic.getUri.toAbsolutePath,
              diagnostic.getDiagnostics.asScala.toSeq,
              isReset = true,
            )
          )
        value
      }
    processFuture.future.foreach { result =>
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
      buildTool: BloopInstallProvider,
      digest: String,
  ): CancelableFuture[WorkspaceLoadedStatus] =
    synchronized {
      oldInstallResult(digest) match {
        case Some(result)
            if result != WorkspaceLoadedStatus.Duplicate(Status.Requested) =>
          scribe.info(s"skipping build import with status '${result.name}'")
          CancelableFuture.successful(result)
        case _ =>
          if (userConfig().shouldAutoImportNewProject) {
            runUnconditionally(buildTool)
          } else {
            scribe.debug("Awaiting user response...")
            implicit val cancelSwitch = CancelSwitch(Promise[Unit]())
            (for {
              userResponse <- requestImport(
                buildTools,
                buildTool,
                languageClient,
                digest,
              ).withInterrupt
              installResult <- {
                if (userResponse.isYes) {
                  runUnconditionally(buildTool).withInterrupt
                } else {
                  // Don't spam the user with requests during rapid build changes.
                  notification.dismiss(2, TimeUnit.MINUTES)
                  Interruptable.successful(WorkspaceLoadedStatus.Rejected)
                }
              }
            } yield installResult).toCancellable
          }
      }
    }

  private def persistChecksumStatus(
      status: Status,
      buildTool: BloopInstallProvider,
  ): Unit = {
    buildTool.digestWithRetry(workspace).foreach { checksum =>
      tables.digests.setStatus(checksum, status)
    }
  }

  private def requestImport(
      buildTools: BuildTools,
      buildTool: BloopInstallProvider,
      languageClient: MetalsLanguageClient,
      digest: String,
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
    tables.digests.setStatus(digest, Status.Requested)
    val (params, yes) =
      if (buildTools.isBloop(buildTool.projectRoot)) {
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
