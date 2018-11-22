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
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.BuildTool.Sbt
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SbtChecksum.Status
import scala.meta.io.AbsolutePath
import scala.util.Success

/**
 * Runs `sbt bloopInstall` processes.
 *
 * Handles responsibilities like:
 * - persist .metals/sbt.md5 checksum file after successful bloopInstall
 * - automatically install sbt-bloop and sbt-metals via -addPluginSbt feature
 *   introduced in sbt v1.2.0.
 */
final class BloopInstall(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    sh: ScheduledExecutorService,
    buildTools: BuildTools,
    time: Time,
    tables: Tables,
    messages: Messages,
    config: MetalsServerConfig
)(implicit ec: ExecutionContext, statusBar: StatusBar)
    extends Cancelable {
  import messages._
  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = {
    cancelables.cancel()
  }

  lazy val sbtLauncher: AbsolutePath = {
    // NOTE(olafur) Use embedded sbt-launch.jar instead of user `sbt` command because
    // we can't rely on `sbt` resolving correctly when using system processes, at least
    // it failed on Windows when I tried it.
    val embeddedLauncher = this.getClass.getResourceAsStream("/sbt-launch.jar")
    val out = Files.createTempDirectory("metals").resolve("sbt-launch.jar")
    out.toFile.deleteOnExit()
    Files.copy(embeddedLauncher, out)
    AbsolutePath(out)
  }

  override def toString: String = s"BloopInstall($workspace)"

  def runUnconditionally(sbt: Sbt): Future[BloopInstallResult] = {
    BloopInstall.writeGlobalPluginFile(sbt)
    BloopInstall.workAroundIssue4395()
    val elapsed = new Timer(time)
    val handler = new BloopInstall.ProcessHandler()
    val javaArgs = List[String](
      "java",
      "-jar",
      sbtLauncher.toString(),
      s"""-Dscalameta.version=4.0.0-163560a8""",
      s"""-Djline.terminal=jline.UnsupportedTerminal""",
      s"""-Dsbt.log.noformat=true""",
      s"""-Dfile.encoding=UTF-8"""
    )
    val sbtArgs = List[String](
      s"""metalsEnable""",
      s"""bloopInstall"""
    )
    val allArgs = javaArgs ++ sbtArgs
    val pb = new NuProcessBuilder(handler, allArgs.asJava)
    pprint.log(workspace)
    pb.setCwd(workspace.toNIO)
    pb.environment().put("COURSIER_PROGRESS", "disable")
    pb.environment().put("METALS_ENABLED", "true")
    val runningProcess = pb.start()
    val prettyArgs = ("sbt" :: sbtArgs).mkString(" ")
    scribe.info(s"running '$prettyArgs'")
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
    taskResponse.asScala.foreach { item =>
      if (item.cancel) {
        scribe.info("User cancelled build import")
        handler.completeProcess.complete(
          Success(BloopInstallResult.Cancelled)
        )
        BloopInstall.destroyProcess(runningProcess)
      }
    }
    if (!config.isExtensionsEnabled) {
      processFuture.trackInStatusBar("Running sbt bloopInstall")
    }
    cancelables
      .add(() => BloopInstall.destroyProcess(runningProcess))
      .add(() => taskResponse.cancel(true))
    processFuture
  }

  private val pendingChange = new AtomicBoolean(false)
  private val notification = tables.dismissedNotifications.ImportChanges
  def reimportIfChanged(forceImport: Boolean)(
      implicit ec: ExecutionContextExecutorService
  ): Future[BloopInstallResult] = {
    for {
      sbt <- buildTools.asSbt.orElse {
        scribe.warn("Unable to parse sbt build")
        None
      }
      current <- SbtChecksum.current(workspace)
      if forceImport || (tables.sbtChecksums.getStatus(current) match {
        case Some(status) if !status.isCancelled && !status.isFailed =>
          scribe.info(s"skipping build import with status '$status'")
          false
        case _ =>
          true
      })
      if forceImport || {
        val isDismissed = notification.isDismissed
        if (isDismissed) {
          scribe.info("skipping build import with status 'Dismissed'")
        }
        !isDismissed
      }
      if forceImport || pendingChange.compareAndSet(false, true)
    } yield {
      tables.sbtChecksums.setStatus(current, Status.Requested)
      val request = for {
        userResponse <- requestImport(buildTools, languageClient, forceImport)
        installResult <- {
          if (userResponse.isYes) {
            SbtChecksum
              .current(workspace)
              .foreach { checksum =>
                tables.sbtChecksums.setStatus(checksum, Status.Started)
              }
            runUnconditionally(sbt)
          } else {
            // Don't spam the user with requests during rapid build changes.
            notification.dismiss(2, TimeUnit.MINUTES)
            Future.successful(BloopInstallResult.Rejected)
          }
        }
      } yield {
        for {
          status <- installResult.toChecksumStatus
          checksum <- SbtChecksum.current(workspace)
        } {
          tables.sbtChecksums.setStatus(checksum, status)
        }
        installResult
      }
      request.onComplete(_ => pendingChange.set(false))
      request
    }

  }.getOrElse(Future.successful(BloopInstallResult.Unchanged))

  private def requestImport(
      buildTools: BuildTools,
      languageClient: MetalsLanguageClient,
      forceImport: Boolean
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
    if (forceImport) Future.successful(Confirmation.Yes)
    else {
      if (buildTools.isBloop) {
        languageClient
          .showMessageRequest(ReimportSbtProject.params)
          .asScala
          .map { item =>
            if (item == dontShowAgain) {
              notification.dismissForever()
            }
            Confirmation.fromBoolean(item == ReimportSbtProject.yes)
          }
      } else {
        languageClient
          .showMessageRequest(ImportBuildViaBloop.params)
          .asScala
          .map { item =>
            if (item == dontShowAgain) {
              notification.dismissForever()
            }
            Confirmation.fromBoolean(item == ImportBuildViaBloop.yes)
          }
      }
    }
  }

}

object BloopInstall {

  // Creates ~/.sbt/1.0/plugins if it doesn't exist, see
  // https://github.com/sbt/sbt/issues/4395
  private def workAroundIssue4395(): Unit = {
    val plugins = pluginsDirectory("1.0")
    Files.createDirectories(plugins.toNIO)
  }

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
      () // do nothing
    } else {
      Files.write(destination.toNIO, bytes)
    }
  }

  /**
   * Contents of metals.sbt file that is installed globally.
   */
  private def globalMetalsSbt: String =
    s"""|// DO NOT EDIT! This file is auto-generated.
        |// By default, this file does not do anything.
        |// If the environment variable METALS_ENABLED has the value 'true',
        |// then this file enables sbt-metals and sbt-bloop.
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
