package scala.meta.internal.builds

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Confirmation
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.Tables
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem

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
    shellRunner: ShellRunner
)(implicit ec: ExecutionContext) {

  override def toString: String = s"BloopInstall($workspace)"

  def runUnconditionally(buildTool: BuildTool): Future[BloopInstallResult] = {
    buildTool.bloopInstall(
      workspace,
      languageClient,
      args => {
        scribe.info(s"running '${args.mkString(" ")}'")
        val process = runArgumentsUnconditionally(buildTool, args)
        process.foreach { e =>
          if (e.isFailed) {
            // Record the exact command that failed to help troubleshooting.
            scribe.error(s"$buildTool command failed: ${args.mkString(" ")}")
          }
        }
        process
      }
    )
  }

  private def runArgumentsUnconditionally(
      buildTool: BuildTool,
      args: List[String]
  ): Future[BloopInstallResult] = {
    persistChecksumStatus(Status.Started, buildTool)
    val processFuture = shellRunner
      .run(
        s"${buildTool.executableName} bloopInstall",
        args,
        workspace,
        buildTool.redirectErrorOutput,
        Map(
          "COURSIER_PROGRESS" -> "disable",
          "METALS_ENABLED" -> "true",
          "SCALAMETA_VERSION" -> BuildInfo.semanticdbVersion
        )
      )
      .map {
        case ExitCodes.Success => BloopInstallResult.Installed
        case ExitCodes.Cancel => BloopInstallResult.Cancelled
        case result => BloopInstallResult.Failed(result)
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

  private def oldInstallResult(digest: String): Option[BloopInstallResult] = {
    if (notification.isDismissed) {
      Some(BloopInstallResult.Dismissed)
    } else {
      tables.digests.last().collect {
        case Digest(md5, status, _) if md5 == digest =>
          BloopInstallResult.Duplicate(status)
      }
    }
  }

  // NOTE(olafur) there's a chance that we get two build change notifications in
  // a very short period due to duplicate `didSave` and file watching
  // notifications. This method is synchronized to prevent asking the user
  // twice whether to import the build.
  def runIfApproved(
      buildTool: BuildTool,
      digest: String
  ): Future[BloopInstallResult] =
    synchronized {
      oldInstallResult(digest) match {
        case Some(result) =>
          scribe.info(s"skipping build import with status '${result.name}'")
          Future.successful(result)
        case None =>
          for {
            userResponse <- requestImport(
              buildTools,
              buildTool,
              languageClient,
              digest
            )
            installResult <- {
              if (userResponse.isYes) {
                runUnconditionally(buildTool)
              } else {
                // Don't spam the user with requests during rapid build changes.
                notification.dismiss(2, TimeUnit.MINUTES)
                Future.successful(BloopInstallResult.Rejected)
              }
            }
          } yield installResult
      }
    }

  def checkForChosenBuildTool(
      buildTools: List[BuildTool]
  ): Future[Option[BuildTool]] =
    tables.buildTool.selectedBuildTool match {
      case Some(chosen) =>
        Future(buildTools.find(_.executableName == chosen))
      case None => requestBuildToolChoice(buildTools)
    }

  private def persistChecksumStatus(
      status: Status,
      buildTool: BuildTool
  ): Unit = {
    buildTool.digest(workspace).foreach { checksum =>
      tables.digests.setStatus(checksum, status)
    }
  }

  private def requestImport(
      buildTools: BuildTools,
      buildTool: BuildTool,
      languageClient: MetalsLanguageClient,
      digest: String
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
