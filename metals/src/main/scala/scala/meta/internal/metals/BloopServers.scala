package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Interruptable.MetalsCancelException
import scala.meta.internal.metals.Messages.OldBloopVersionRunning
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BspConnection
import bloop.rifle.BspConnectionAddress

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

  private def metalsJavaHome = sys.props
    .get("java.home")
    .orElse(sys.env.get("JAVA_HOME"))
  private val bloopWorkingDir = createBloopWorkingDir
  private val bloopDaemonDir = bloopWorkingDir.resolve("daemon")
  private val folderIdMap = TrieMap.empty[AbsolutePath, Int]

  def shutdownServer(): Boolean = {
    // user config is just useful for starting a new bloop server or connection
    val retCode = BloopRifle.exit(
      bloopConfig(userConfig = None, projectRoot = None),
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

  /**
   * Stop a Bloop server that reported itself as running but never answered, so
   * the next connection attempt can cold-start a fresh one.
   *
   * No-op unless this connection reused a pre-existing server: a server we just
   * started that fails to come up is a startup problem, not a wedge, and exiting
   * it would only loop. `BloopRifle.exit` sends a synchronous `ng-stop` over the
   * same (possibly stuck) socket, so we run it off the calling thread and bound
   * it with a timeout. The next `connect` only cold-starts a fresh server if the
   * old one is actually gone (`BloopRifle.check` is socket-based), so we wait for
   * it to stop and, if it can't be stopped, fail with an actionable message
   * rather than silently reconnecting to the same wedged process.
   */
  private def recoverFromWedgedServer(
      connectedToPreexistingServer: AtomicBoolean
  ): Future[Unit] =
    if (connectedToPreexistingServer.get()) {
      val config = bloopConfig(userConfig = None, projectRoot = None)
      scribe.warn(
        "Bloop server was reported as running but didn't respond; " +
          "stopping it so a fresh one can be started."
      )
      // `ng-stop` is a synchronous call over the same (possibly stuck) socket,
      // so run it on a dedicated daemon thread: a truly hung server then leaks
      // only this isolated thread instead of occupying an execution-context one
      // after recovery has moved on.
      val exit = new Thread("bloop-exit-on-recovery") {
        override def run(): Unit =
          try {
            BloopRifle.exit(config, bloopWorkingDir.toNIO, bloopLogger)
            ()
          } catch {
            case NonFatal(e) =>
              scribe.warn("Couldn't cleanly stop the Bloop server.", e)
          }
      }
      exit.setDaemon(true)
      exit.start()
      // Wait — without blocking a thread — for the server to actually go down.
      // `check` is socket-based, so the retry only cold-starts a fresh server
      // once the old one is really gone; otherwise fail with actionable guidance.
      awaitBloopStopped(
        config,
        System.currentTimeMillis() + RecoveryTimeoutMs,
      ).map {
        case true => ()
        case false =>
          // Show the actionable guidance directly: the reconnect path doesn't go
          // through `ConnectionProvider`, so this is the only message there. Throw
          // a marker so the initial-connect path doesn't also stack its generic
          // "failed to connect" message on top of this one.
          languageClient.showMessage(Messages.UnresponsiveBloopServer.params())
          throw new AlreadyReportedConnectException(
            Messages.UnresponsiveBloopServer.message
          )
      }
    } else Future.unit

  /**
   * Poll `BloopRifle.check` until Bloop is down or `deadline` (epoch ms) passes,
   * scheduling the delays on `sh` rather than blocking a thread.
   */
  private def awaitBloopStopped(
      config: BloopRifleConfig,
      deadline: Long,
  ): Future[Boolean] = {
    val stopped = Promise[Boolean]()
    def poll(): Unit =
      try {
        if (!BloopRifle.check(config, bloopLogger)) stopped.trySuccess(true)
        else if (System.currentTimeMillis() >= deadline)
          stopped.trySuccess(false)
        else {
          sh.schedule(
            new Runnable { def run(): Unit = poll() },
            RecoveryPollIntervalMs,
            TimeUnit.MILLISECONDS,
          )
          ()
        }
      } catch {
        case NonFatal(e) =>
          // A scheduled poll runs on `sh`, where a thrown exception would be
          // swallowed and leave `stopped` pending forever, so complete it here.
          scribe.warn(
            "Error while checking whether the Bloop server stopped.",
            e,
          )
          stopped.trySuccess(false)
          ()
      }
    poll()
    stopped.future
  }

  def newServer(
      projectRoot: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      userConfiguration: () => UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {
    // Set by `connect` to whether it reused an already-running Bloop server;
    // read by `recoverFromWedgedServer` to decide whether to force a restart.
    // Local to this connection so concurrent folder connects don't race on it.
    val connectedToPreexistingServer = new AtomicBoolean(false)
    BuildServerConnection
      .fromSockets(
        projectRoot,
        bspTraceRoot,
        client,
        languageClient,
        () =>
          connect(
            projectRoot,
            userConfiguration(),
            connectedToPreexistingServer,
          ),
        tables.dismissedNotifications.ReconnectBsp,
        tables.dismissedNotifications.RequestTimeout,
        serverConfig,
        userConfiguration(),
        name,
        bspStatusOpt,
        workDoneProgress = workDoneProgress,
        recoverConnection =
          () => recoverFromWedgedServer(connectedToPreexistingServer),
      )
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

  /* Added after 1.3.4, we can probably remove this in a future version.
   */
  private def checkOldBloopRunning(): Future[Unit] = try {
    metalsJavaHome.flatMap { home =>
      ShellRunner
        .runSync(
          List(s"${home}/bin/jps", "-l"),
          bloopWorkingDir,
          redirectErrorOutput = false,
        )
        .flatMap { processes =>
          "(\\d+) bloop[.]Server".r
            .findFirstMatchIn(processes)
            .map(_.group(1).toInt)
        }
    } match {
      case None => Future.unit
      case Some(value) =>
        def killOldBloop(): Unit =
          ShellRunner.runSync(
            List("kill", "-9", value.toString()),
            bloopWorkingDir,
            redirectErrorOutput = false,
          )
        languageClient
          .showMessageRequest(
            OldBloopVersionRunning.params(),
            ConnectionProvider.ConnectRequestCancelationGroup,
            throw MetalsCancelException,
          )
          .map { res =>
            Option(res) match {
              case Some(item) if item == OldBloopVersionRunning.yes =>
                killOldBloop()
              case Some(Messages.missedByUser) =>
                languageClient.showMessage(
                  OldBloopVersionRunning.killingBloopParams()
                )
                killOldBloop()
              case _ =>
            }
          }
    }
  } catch {
    case NonFatal(e) =>
      scribe.warn(
        "Could not check if the deprecated bloop server is still running",
        e,
      )
      Future.unit
  }

  private def bloopConfig(
      userConfig: Option[UserConfiguration],
      projectRoot: Option[AbsolutePath],
  ) = {

    val addr = BloopRifleConfig.Address.DomainSocket(
      bloopDaemonDir.toNIO
    )

    val config = BloopRifleConfig
      .default(addr, fetchBloop _, bloopWorkingDir.toNIO.toFile)
      .copy(
        bspSocketOrPort = Some { () =>
          val pid =
            ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@').toInt
          val dir = bloopWorkingDir.resolve("bsp").toNIO
          if (!Files.exists(dir)) {
            Files.createDirectories(dir.getParent)
            if (Properties.isWin)
              Files.createDirectory(dir)
            else
              Files.createDirectory(
                dir,
                PosixFilePermissions
                  .asFileAttribute(PosixFilePermissions.fromString("rwx------")),
              )
          }
          // We need to use a different socket for each folder, since it's a separate connection
          val uniqueFolderId = projectRoot
            .map { path =>
              this.folderIdMap
                .getOrElseUpdate(path, connectionCounter.incrementAndGet())
                .toString()
            }
            .getOrElse("")

          val socketPath = dir.resolve(s"$pid-$uniqueFolderId")
          if (Files.exists(socketPath))
            try Files.delete(socketPath)
            catch {
              case NonFatal(e) =>
                // This seems to be happening sometimes in tests
                scribe
                  .debug("Unexpected error while deleting the BSP socket", e)
            }
          BspConnectionAddress.UnixDomainSocket(socketPath)
        },
        bspStdout = bloopLogger.bloopBspStdout,
        bspStderr = bloopLogger.bloopBspStderr,
      )

    val additionalProperties = List(
      Properties
        .propOrNone("coursier.credentials")
        .map(value => s"-Dcoursier.credentials=$value")
    ).flatten
    userConfig.map(_.bloopJvmProperties.properties).flatten match {
      case Some(opts) if opts.nonEmpty =>
        config.copy(javaOpts = opts ++ additionalProperties)
      case _ => config.copy(javaOpts = config.javaOpts ++ additionalProperties)
    }
  }

  private def startNewServer(
      config: BloopRifleConfig,
      userConfiguration: UserConfiguration,
  ): Future[Unit] = {
    scribe.info("No running Bloop server found, starting one.")
    val ext = if (Properties.isWin) ".exe" else ""
    val javaCommand = metalsJavaHome match {
      case Some(metalsJavaHome) =>
        Paths.get(metalsJavaHome).resolve(s"bin/java$ext").toString
      case None => "java"
    }
    val version =
      userConfiguration.bloopVersion.getOrElse(defaultBloopVersion)
    checkOldBloopRunning().flatMap { _ =>
      BloopRifle.startServer(
        config,
        sh,
        bloopLogger,
        version,
        javaCommand,
      )
    }
  }

  private def connect(
      projectRoot: AbsolutePath,
      userConfiguration: UserConfiguration,
      connectedToPreexistingServer: AtomicBoolean,
  ): Future[SocketConnection] = {
    val config = bloopConfig(Some(userConfiguration), Some(projectRoot))

    val maybeStartBloop =
      if (BloopRifle.check(config, bloopLogger)) {
        scribe.info("Found a Bloop server running")
        connectedToPreexistingServer.set(true)
        Future.unit
      } else {
        connectedToPreexistingServer.set(false)
        startNewServer(config, userConfiguration)
      }

    def openConnection(
        conn: BspConnection,
        period: FiniteDuration,
        timeout: FiniteDuration,
    ): Socket = {

      @tailrec
      def create(stopAt: Long): Socket = {
        val maybeSocket =
          try Right(conn.openSocket(period, timeout))
          catch {
            // Any failure while waiting for the BSP socket means the connection
            // didn't materialize. bloop-rifle throws a plain RuntimeException
            // via `sys.error` when the socket never opens, so treat every
            // failure like a connect failure and normalize it to the
            // `IOException` thrown below, which the recovery path acts on.
            case NonFatal(e) => Left(e)
          }
        maybeSocket match {
          case Right(socket) => socket
          case Left(e) =>
            if (System.currentTimeMillis() >= stopAt)
              throw new IOException(s"Can't connect to ${conn.address}", e)
            else {
              Thread.sleep(period.toMillis)
              create(stopAt)
            }
        }
      }

      create(System.currentTimeMillis() + timeout.toMillis)
    }

    def openBspConn = Future {
      val conn = BloopRifle.bsp(
        config,
        bloopWorkingDir.toNIO,
        bloopLogger,
      )

      val finished = Promise[Unit]()
      conn.closed.ignoreValue.onComplete(finished.tryComplete)

      val socket = openConnection(conn, config.period, config.timeout)

      SocketConnection(
        name,
        new ClosableOutputStream(socket.getOutputStream, "Bloop OutputStream"),
        new QuietInputStream(socket.getInputStream, "Bloop InputStream"),
        Nil,
        finished,
      )
    }

    for {
      _ <- maybeStartBloop
      conn <- openBspConn
    } yield conn
  }
}

object BloopServers {
  val name = "Bloop"

  // How long to wait for a wedged Bloop server to stop before giving up.
  private val RecoveryTimeoutMs = 10000L

  // How often to poll whether the wedged Bloop server has stopped.
  private val RecoveryPollIntervalMs = 100L

  // Needed for creating unique socket files for each bloop connection
  private[BloopServers] val connectionCounter = new AtomicInteger(0)

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
