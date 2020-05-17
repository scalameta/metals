package scala.meta.internal.metals

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.zaxxer.nuprocess.NuProcessBuilder
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.internal.process.ProcessHandler
import scala.meta.internal.process.ExitCodes
import scala.meta.internal.metals.Messages._
import org.eclipse.lsp4j.MessageActionItem
import scala.meta.internal.semver.SemVer

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
    sh: ScheduledExecutorService,
    buildTools: BuildTools,
    time: Time,
    tables: Tables,
    embedded: Embedded,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext)
    extends Cancelable {
  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = {
    cancelables.cancel()
  }

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
    val elapsed = new Timer(time)
    val handler = ProcessHandler(
      joinErrorWithInfo = buildTool.redirectErrorOutput
    )
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(workspace.toNIO)
    userConfig().javaHome.foreach(pb.environment().put("JAVA_HOME", _))
    pb.environment().put("COURSIER_PROGRESS", "disable")
    pb.environment().put("METALS_ENABLED", "true")
    pb.environment().put("SCALAMETA_VERSION", BuildInfo.semanticdbVersion)
    val runningProcess = pb.start()
    // NOTE(olafur): older versions of VS Code don't respect cancellation of
    // window/showMessageRequest, meaning the "cancel build import" button
    // stays forever in view even after successful build import. In newer
    // VS Code versions the message is hidden after a delay.
    val taskResponse =
      languageClient.metalsSlowTask(
        Messages.bloopInstallProgress(buildTool.executableName)
      )
    handler.response = Some(taskResponse)
    val processFuture = handler.completeProcess.future.map { result =>
      taskResponse.cancel(false)
      scribe.info(
        s"time: ran '${buildTool.executableName} bloopInstall' in $elapsed"
      )
      result match {
        case ExitCodes.Success => BloopInstallResult.Installed
        case ExitCodes.Cancel => BloopInstallResult.Cancelled
        case _ => BloopInstallResult.Failed(result)
      }
    }
    statusBar.trackFuture(
      s"Running ${buildTool.executableName} bloopInstall",
      processFuture
    )
    taskResponse.asScala.foreach { item =>
      if (item.cancel) {
        scribe.info("user cancelled build import")
        handler.completeProcess.trySuccess(ExitCodes.Cancel)
        ProcessHandler.destroyProcess(runningProcess)
      }
    }
    cancelables
      .add(() => ProcessHandler.destroyProcess(runningProcess))
      .add(() => taskResponse.cancel(false))

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
  ): Future[BloopInstallResult] = synchronized {
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
        val selectedBuildTool =
          buildTools.find(buildTool =>
            new MessageActionItem(buildTool.executableName) == choice
          )
        selectedBuildTool match {
          case Some(buildTool) => {
            val isCompatibleVersion = SemVer.isCompatibleVersion(
              buildTool.minimumVersion,
              buildTool.version
            )
            if (isCompatibleVersion) {
              tables.buildTool.chooseBuildTool(choice.getTitle)
              Some(buildTool)
            } else {
              scribe.warn(
                s"Unsupported $buildTool version ${buildTool.version}"
              )
              languageClient.showMessage(
                Messages.IncompatibleBuildToolVersion.params(buildTool)
              )
              None
            }
          }
          case None => None
        }
      }
  }
}
