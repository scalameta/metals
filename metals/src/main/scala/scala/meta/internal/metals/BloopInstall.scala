package scala.meta.internal.metals

import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import fansi.ErrorMode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.BuildTool.Sbt
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SbtDigest.Status
import scala.meta.io.AbsolutePath
import scala.util.Success

/**
 * Runs `sbt bloopInstall` processes.
 *
 * Handles responsibilities like:
 * - install metals.sbt as a global sbt plugin
 * - launching embedded sbt-launch.jar via system process
 * - reporting client about `sbt bloopInstall` progress
 */
final class BloopInstall(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    sh: ScheduledExecutorService,
    buildTools: BuildTools,
    time: Time,
    tables: Tables,
    messages: Messages,
    config: MetalsServerConfig,
    embedded: Embedded,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext)
    extends Cancelable {
  import messages._
  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = {
    cancelables.cancel()
  }

  override def toString: String = s"BloopInstall($workspace)"

  def runUnconditionally(sbt: Sbt): Future[BloopInstallResult] = {
    val sbtArgs = List[String](
      "metalsEnable",
      "bloopInstall"
    )
    val args: List[String] = userConfig().sbtScript match {
      case Some(script) =>
        script :: sbtArgs
      case None =>
        val javaArgs = List[String](
          JavaBinary(userConfig().javaHome),
          "-Djline.terminal=jline.UnsupportedTerminal",
          "-Dsbt.log.noformat=true",
          "-Dfile.encoding=UTF-8"
        )
        val jarArgs = List(
          "-jar",
          embedded.sbtLauncher.toString()
        )
        List(
          javaArgs,
          SbtOpts.loadFrom(workspace),
          JvmOpts.loadFrom(workspace),
          userConfig().sbtOpts,
          jarArgs,
          sbtArgs
        ).flatten
    }
    scribe.info(s"running 'sbt ${sbtArgs.mkString(" ")}'")
    val result = runArgumentsUnconditionally(sbt, args)
    result.foreach { e =>
      if (e.isFailed) {
        // Record the exact command that failed to help troubleshooting.
        scribe.error(s"sbt command failed: ${args.mkString(" ")}")
      }
    }
    result
  }

  private def runArgumentsUnconditionally(
      sbt: Sbt,
      args: List[String]
  ): Future[BloopInstallResult] = {
    persistChecksumStatus(Status.Started)
    BloopInstall.writeGlobalPluginFile(sbt)
    val elapsed = new Timer(time)
    val handler = new BloopInstall.ProcessHandler()
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(workspace.toNIO)
    pb.environment().put("COURSIER_PROGRESS", "disable")
    pb.environment().put("METALS_ENABLED", "true")
    pb.environment().put("SCALAMETA_VERSION", BuildInfo.semanticdbVersion)
    val runningProcess = pb.start()
    // NOTE(olafur): older versions of VS Code don't respect cancellation of
    // window/showMessageRequest, meaning the "cancel build import" button
    // stays forever in view even after successful build import. In newer
    // VS Code versions the message is hidden after a delay.
    val taskResponse =
      languageClient.metalsSlowTask(Messages.BloopInstallProgress)
    handler.response = Some(taskResponse)
    val processFuture = handler.completeProcess.future.map { result =>
      taskResponse.cancel(true)
      scribe.info(s"time: Ran 'sbt bloopInstall' in $elapsed")
      result
    }
    statusBar.trackFuture("Running sbt bloopInstall", processFuture)
    taskResponse.asScala.foreach { item =>
      if (item.cancel) {
        scribe.info("User cancelled build import")
        handler.completeProcess.complete(
          Success(BloopInstallResult.Cancelled)
        )
        BloopInstall.destroyProcess(runningProcess)
      }
    }
    cancelables
      .add(() => BloopInstall.destroyProcess(runningProcess))
      .add(() => taskResponse.cancel(true))

    processFuture.foreach(_.toChecksumStatus.foreach(persistChecksumStatus))
    processFuture
  }

  private val notification = tables.dismissedNotifications.ImportChanges

  private def oldInstallResult(digest: String): Option[BloopInstallResult] = {
    if (notification.isDismissed) {
      Some(BloopInstallResult.Dismissed)
    } else {
      tables.sbtDigests.last().collect {
        case SbtDigest(md5, status, _) if md5 == digest =>
          BloopInstallResult.Duplicate(status)
      }
    }
  }

  def runIfApproved(sbt: Sbt, digest: String): Future[BloopInstallResult] = {
    oldInstallResult(digest) match {
      case Some(result) =>
        scribe.info(s"skipping build import with status '${result.name}'")
        Future.successful(result)
      case None =>
        for {
          userResponse <- requestImport(buildTools, languageClient, digest)
          installResult <- {
            if (userResponse.isYes) {
              runUnconditionally(sbt)
            } else {
              // Don't spam the user with requests during rapid build changes.
              notification.dismiss(2, TimeUnit.MINUTES)
              Future.successful(BloopInstallResult.Rejected)
            }
          }
        } yield installResult
    }
  }

  private def persistChecksumStatus(status: Status): Unit = {
    SbtDigest.foreach(workspace) { checksum =>
      tables.sbtDigests.setStatus(checksum, status)
    }
  }

  private def requestImport(
      buildTools: BuildTools,
      languageClient: MetalsLanguageClient,
      digest: String
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
    tables.sbtDigests.setStatus(digest, Status.Requested)
    if (buildTools.isBloop) {
      languageClient
        .showMessageRequest(ImportBuildChanges.params)
        .asScala
        .map { item =>
          if (item == dontShowAgain) {
            notification.dismissForever()
          }
          Confirmation.fromBoolean(item == ImportBuildChanges.yes)
        }
    } else {
      languageClient
        .showMessageRequest(ImportBuild.params)
        .asScala
        .map { item =>
          if (item == dontShowAgain) {
            notification.dismissForever()
          }
          Confirmation.fromBoolean(item == ImportBuild.yes)
        }
    }
  }

}

object BloopInstall {

  def pluginsDirectory(version: String): AbsolutePath = {
    AbsolutePath(System.getProperty("user.home"))
      .resolve(".sbt")
      .resolve(version)
      .resolve("plugins")
  }

  private def writeGlobalPluginFile(sbt: Sbt): Unit = {
    val plugins =
      if (sbt.version.startsWith("0.13")) pluginsDirectory("0.13")
      else pluginsDirectory("1.0")
    Files.createDirectories(plugins.toNIO)
    val bytes = globalMetalsSbt.getBytes(StandardCharsets.UTF_8)
    val destination = plugins.resolve("metals.sbt")
    if (destination.isFile && destination.readAllBytes.sameElements(bytes)) {
      // Do nothing if the file is unchanged. If we write to the file unconditionally
      // we risk triggering sbt re-compilation of global plugins that slows down
      // build import greatly. If somebody validates it doesn't affect load times
      // then feel free to remove this guard.
      ()
    } else {
      Files.write(destination.toNIO, bytes)
    }
  }

  /**
   * Contents of metals.sbt file that is installed globally.
   */
  private def globalMetalsSbt: String = {
    val resolvers =
      if (BuildInfo.metalsVersion.endsWith("-SNAPSHOT")) {
        """|resolvers ++= {
           |  if (System.getenv("METALS_ENABLED") == "true") {
           |    List(Resolver.sonatypeRepo("snapshots"))
           |  } else {
           |    List()
           |  }
           |}
           |""".stripMargin
      } else {
        ""
      }
    s"""|// DO NOT EDIT! This file is auto-generated.
        |// By default, this file does not do anything.
        |// If the environment variable METALS_ENABLED has the value 'true',
        |// then this file enables sbt-metals and sbt-bloop.
        |$resolvers
        |libraryDependencies := {
        |  import Defaults.sbtPluginExtra
        |  val oldDependencies = libraryDependencies.value
        |  if (System.getenv("METALS_ENABLED") == "true") {
        |    val bloopModule = "ch.epfl.scala" % "sbt-bloop" % "${BuildInfo.sbtBloopVersion}"
        |    val metalsModule = "org.scalameta" % "sbt-metals" % "${BuildInfo.metalsVersion}"
        |    val sbtVersion = Keys.sbtBinaryVersion.in(pluginCrossBuild).value
        |    val scalaVersion = Keys.scalaBinaryVersion.in(update).value
        |    val bloopPlugin = sbtPluginExtra(bloopModule, sbtVersion, scalaVersion)
        |    val metalsPlugin = sbtPluginExtra(metalsModule, sbtVersion, scalaVersion)
        |    List(bloopPlugin, metalsPlugin) ++ oldDependencies.filterNot { dep =>
        |      (dep.organization == "ch.epfl.scala" && dep.name == "sbt-bloop") ||
        |      (dep.organization == "org.scalameta" && dep.name == "sbt-metals")
        |    }
        |  } else {
        |    oldDependencies
        |  }
        |}
        |""".stripMargin
  }

  /**
   * First tries to destroy the process gracefully, with fallback to forcefully.
   */
  private def destroyProcess(process: NuProcess): Unit = {
    process.destroy(false)
    val exit = process.waitFor(2, TimeUnit.SECONDS)
    if (exit == Integer.MIN_VALUE) {
      // timeout exceeded, kill process forcefully.
      process.destroy(true)
      process.waitFor(2, TimeUnit.SECONDS)
    }
  }

  /**
   * Converts running system processing into Future[BloopInstallResult].
   */
  private class ProcessHandler() extends NuAbstractProcessHandler {
    var response: Option[CompletableFuture[_]] = None
    val completeProcess = Promise[BloopInstallResult]()

    override def onStart(nuProcess: NuProcess): Unit = {
      nuProcess.closeStdin(false)
    }

    override def onExit(statusCode: Int): Unit = {
      if (!completeProcess.isCompleted) {
        if (statusCode == 0) {
          completeProcess.trySuccess(BloopInstallResult.Installed)
        } else {
          completeProcess.trySuccess(BloopInstallResult.Failed(statusCode))
        }
      }
      scribe.info(s"sbt exit: $statusCode")
      response.foreach(_.cancel(true))
    }

    override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
      log(closed, buffer)(out => scribe.info(out))
    }

    override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
      log(closed, buffer)(out => scribe.error(out))
    }

    private def log(closed: Boolean, buffer: ByteBuffer)(
        fn: String => Unit
    ): Unit = {
      if (!closed) {
        val text = toPlainString(buffer).trim
        if (text.nonEmpty) {
          fn(text)
        }
      }
    }

    private def toPlainString(buffer: ByteBuffer): String = {
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      val ansiString = new String(bytes, StandardCharsets.UTF_8)
      fansi.Str(ansiString, ErrorMode.Sanitize).plainText
    }
  }
}
