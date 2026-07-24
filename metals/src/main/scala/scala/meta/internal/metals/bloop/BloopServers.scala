package scala.meta.internal.metals.bloop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsProjectDirectories
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.buildserver.BuildServerConnection
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger

/**
 * Establishes a connection with a bloop server using Bloop Launcher.
 *
 * Connects to a running bloop server instance if it is installed on the user
 * machine and starts a new one if it isn't. Alternatively user can use the
 * coursier command to launch it:
 *
 * coursier launch ch.epfl.scala:bloopgun-core_2.12:{bloop-version} -- about
 *
 * Eventually, this class may be superseded by "BSP connection protocol":
 * https://build-server-protocol.github.io/docs/server-discovery.html
 */
final class BloopServers(
    client: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
    tables: Tables,
    serverConfig: MetalsServerConfig,
    workDoneProgress: WorkDoneProgress,
    sh: ScheduledExecutorService,
)(implicit ec: ExecutionContextExecutorService) {

  import BloopServers._

  private val bloopWorkingDir = createBloopWorkingDir
  private val bloopDaemonDir = bloopWorkingDir.resolve("daemon")

  private val configFactory =
    new BloopServerConfigFactory(bloopWorkingDir, bloopDaemonDir, bloopLogger)

  def shutdownServer(): Boolean = {
    // user config is just useful for starting a new bloop server or connection
    val retCode = BloopRifle.exit(
      configFactory.bloopConfig(userConfig = None, projectRoot = None),
      bloopWorkingDir.toNIO,
      bloopLogger,
    )
    val result = retCode == 0
    if (!result) {
      scribe.warn("There were issues stopping the Bloop server.")
      scribe.warn(
        "If it doesn't start back up you can run the `build-restart` command manually."
      )
    }
    result
  }

  def newServer(
      projectRoot: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      userConfig: () => UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
      progress: TaskProgress,
  ): Future[BuildServerConnection] = {
    progress.message = "connecting to bloop"
    val connectionFactory = new BloopServerConnectionFactory(
      projectRoot,
      bspTraceRoot,
      client,
      languageClient,
      tables.dismissedNotifications.RequestTimeout,
      tables.dismissedNotifications.ReconnectBsp,
      serverConfig,
      bspStatusOpt,
      workDoneProgress,
      bloopLogger,
      sh,
      bloopWorkingDir,
    ) {

      override protected def userConfiguration(): UserConfiguration =
        userConfig()

      override protected def bloopConfig(
          userConfig: Option[UserConfiguration],
          projectRoot: Option[AbsolutePath],
      ): BloopRifleConfig =
        configFactory.bloopConfig(userConfig, projectRoot)

    }
    connectionFactory
      .fromSockets()
      .recover { case NonFatal(e) =>
        Try(
          // Bloop output
          bloopDaemonDir.resolve("output").readText
        ).foreach {
          scribe.error(_)
        }
        throw e
      }
  }

  /**
   * Ensure Bloop is running the intended version that the user has passed
   * in via UserConfiguration. If not, shut Bloop down and reconnect to it.
   *
   * @param expectedVersion desired version that the user has passed in. This
   *                        could either be a newly passed in version from the
   *                        user or the default Bloop version.
   * @param runningVersion  the current running version of Bloop.
   * @param userDefinedNew  whether or not the user has defined a new version.
   * @param userDefinedOld  whether or not the user has the version running
   *                        defined or if they are just running the default.
   * @param reconnect       function to connect back to the build server.
   */
  def ensureDesiredVersion(
      expectedVersion: String,
      runningVersion: String,
      userDefinedNew: Boolean,
      userDefinedOld: Boolean,
      restartAndConnect: () => Future[BuildChange],
  ): Future[Unit] = {
    val correctVersionRunning = expectedVersion == runningVersion
    val changedToNoVersion = userDefinedOld && !userDefinedNew
    val versionChanged = userDefinedNew && !correctVersionRunning
    val versionRevertedToDefault = changedToNoVersion && !correctVersionRunning

    if (versionRevertedToDefault || versionChanged) {
      if (serverConfig.askToRestartBloop) {
        languageClient
          .showMessageRequest(
            Messages.BloopVersionChange.params()
          )
          .asScala
          .flatMap {
            case item if item == Messages.BloopVersionChange.reconnect =>
              restartAndConnect().ignoreValue
            case _ =>
              Future.unit
          }
      } else {
        restartAndConnect().ignoreValue
      }
    } else {
      Future.unit
    }
  }

  def checkPropertiesChanged(
      old: UserConfiguration,
      newConfig: UserConfiguration,
      restartAndConnect: () => Future[BuildChange],
  ): Future[Unit] = {
    if (old.bloopJvmProperties != newConfig.bloopJvmProperties) {
      old.bloopJvmProperties match {
        case BloopJvmProperties.Uninitialized =>
          scribe.debug(
            s"Bloop JVM properties initialized from uninitialized to ${newConfig.bloopJvmProperties}, no restart needed"
          )
          Future.unit
        case _ =>
          scribe.debug(
            s"Bloop JVM properties changed from ${old.bloopJvmProperties} to ${newConfig.bloopJvmProperties}"
          )
          languageClient
            .showMessageRequest(
              Messages.BloopJvmPropertiesChange.params(),
              defaultTo = () => {
                languageClient.showMessage(
                  Messages.BloopJvmPropertiesChange.notificationParams()
                )
                Messages.BloopJvmPropertiesChange.notNow
              },
            )
            .asScala
            .flatMap {
              case item
                  if item == Messages.BloopJvmPropertiesChange.reconnect =>
                restartAndConnect().ignoreValue
              case _ =>
                Future.unit
            }
      }
    } else {
      Future.unit
    }

  }

  private lazy val bloopLogger: BloopRifleLogger = new BloopRifleLogger {
    def info(msg: => String): Unit = scribe.info(msg)
    def debug(msg: => String, ex: Throwable): Unit = scribe.debug(msg, ex)
    def debug(msg: => String): Unit = scribe.debug(msg)
    def error(msg: => String): Unit = scribe.error(msg)
    def error(msg: => String, ex: Throwable): Unit = scribe.error(msg, ex)

    private def loggingOutputStream(log: String => Unit): OutputStream = {
      new OutputStream {
        private val buf = new ByteArrayOutputStream
        private val lock = new Object
        private def check(): Unit = {
          lock.synchronized {
            val b = buf.toByteArray
            val s = new String(b)
            val idx = s.lastIndexOf("\n")
            if (idx >= 0) {
              s.take(idx + 1).split("\r?\n").foreach(log)
              buf.reset()
              buf.write(s.drop(idx + 1).getBytes)
            }
          }
        }
        def write(b: Int) =
          lock.synchronized {
            buf.write(b)
            if (b == '\n')
              check()
          }
        override def write(b: Array[Byte]) =
          lock.synchronized {
            buf.write(b)
            if (b.exists(_ == '\n')) check()
          }
        override def write(b: Array[Byte], off: Int, len: Int) =
          lock.synchronized {
            buf.write(b, off, len)
            if (b.iterator.drop(off).take(len).exists(_ == '\n')) check()
          }
      }
    }
    def bloopBspStdout: Some[java.io.OutputStream] = Some(
      loggingOutputStream(scribe.debug(_))
    )
    def bloopBspStderr: Some[java.io.OutputStream] = Some(
      loggingOutputStream(scribe.info(_))
    )
    def bloopCliInheritStdout = false
    def bloopCliInheritStderr = false
  }

}

object BloopServers {
  val name = "Bloop"

  // Needed for creating unique socket files for each bloop connection
  private[BloopServers] val connectionCounter = new AtomicInteger(0)

  def nextConnectionNo(): Int = connectionCounter.incrementAndGet()

  def createBloopWorkingDir(implicit ec: ExecutionContext): AbsolutePath = {

    val directory = MetalsProjectDirectories
      .from(
        null,
        null,
        "ScalaCli",
        silent = false,
      )
      .map { bloopDirectories =>
        if (Properties.isMac) bloopDirectories.cacheDir
        else bloopDirectories.dataLocalDir
      }
      .filter(MetalsProjectDirectories.isNotBroken)
    val baseDir = directory match {
      case None =>
        val userHome = Paths.get(System.getProperty("user.home"))
        val potential =
          if (Properties.isWin) userHome.resolve("AppData/Local/ScalaCli/data")
          else if (Properties.isMac) userHome.resolve("Library/Caches/ScalaCli")
          else userHome.resolve(".local/share/scalacli")
        Files.createDirectories(potential)
        if (potential.toFile.exists()) potential
        else
          throw new IllegalStateException(
            s"Could not create directory $potential for Bloop, please try using a different BSP server and reporting your issue."
          )
      case Some(baseDir) =>
        Paths.get(baseDir)
    }
    AbsolutePath(baseDir.resolve("bloop"))
  }

  def fetchBloop(version: String): Either[Throwable, Seq[File]] = {

    val (org, name) = BloopRifleConfig.defaultModule.split(":", -1) match {
      case Array(org0, name0) => (org0, name0)
      case Array(org0, "", name0) =>
        val sbv =
          if (BloopRifleConfig.defaultScalaVersion.startsWith("2."))
            BloopRifleConfig.defaultScalaVersion
              .split('.')
              .take(2)
              .mkString(".")
          else
            BloopRifleConfig.defaultScalaVersion.split('.').head
        (org0, name0 + "_" + sbv)
      case _ =>
        sys.error(
          s"Malformed default Bloop module '${BloopRifleConfig.defaultModule}'"
        )
    }

    try {
      val cp = Embedded
        .downloadDependency(org, name, version)
        .map(_.toFile())
      Right(cp)
    } catch {
      case NonFatal(t) =>
        Left(t)
    }
  }

  def defaultBloopVersion = BloopRifleConfig.defaultVersion

  def minimumBloopVersion = "2.0.17"
}
