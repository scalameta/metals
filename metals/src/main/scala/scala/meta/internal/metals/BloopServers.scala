package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
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
import scala.meta.internal.metals.Messages.OldBloopVersionRunning
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BspConnection
import bloop.rifle.BspConnectionAddress
import dev.dirs.ProjectDirectories

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
    languageClient: MetalsLanguageClient,
    tables: Tables,
    serverConfig: MetalsServerConfig,
    workDoneProgress: WorkDoneProgress,
    sh: ScheduledExecutorService,
)(implicit ec: ExecutionContextExecutorService) {

  import BloopServers._

  private def metalsJavaHome =
    sys.props
      .get("java.home")
      .orElse(sys.env.get("JAVA_HOME"))

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

  def newServer(
      projectRoot: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      userConfiguration: () => UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {
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
          ),
        tables.dismissedNotifications.ReconnectBsp,
        tables.dismissedNotifications.RequestTimeout,
        serverConfig,
        name,
        bspStatusOpt,
        workDoneProgress = workDoneProgress,
      )
      .recover { case NonFatal(e) =>
        Try(
          // Bloop output
          BloopServers.bloopDaemonDir.resolve("output").readText
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
      Future.unit
    }
  }

  def checkPropertiesChanged(
      old: UserConfiguration,
      newConfig: UserConfiguration,
      restartAndConnect: () => Future[BuildChange],
  ): Future[Unit] = {
    if (old.bloopJvmProperties != newConfig.bloopJvmProperties) {
      languageClient
        .showMessageRequest(
          Messages.BloopJvmPropertiesChange.params()
        )
        .asScala
        .flatMap {
          case item if item == Messages.BloopJvmPropertiesChange.reconnect =>
            restartAndConnect().ignoreValue
          case _ =>
            Future.unit
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
        languageClient
          .showMessageRequest(
            OldBloopVersionRunning.params()
          )
          .asScala
          .map { res =>
            Option(res) match {
              case Some(item) if item == OldBloopVersionRunning.yes =>
                ShellRunner.runSync(
                  List("kill", "-9", value.toString()),
                  bloopWorkingDir,
                  redirectErrorOutput = false,
                )
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
          BspConnectionAddress.UnixDomainSocket(socketPath.toFile)
        },
        bspStdout = bloopLogger.bloopBspStdout,
        bspStderr = bloopLogger.bloopBspStderr,
      )

    userConfig.flatMap(_.bloopJvmProperties) match {
      case Some(opts) if opts.nonEmpty => config.copy(javaOpts = opts)
      case _ => config
    }
  }

  private def connect(
      projectRoot: AbsolutePath,
      userConfiguration: UserConfiguration,
  ): Future[SocketConnection] = {
    val config = bloopConfig(Some(userConfiguration), Some(projectRoot))

    val maybeStartBloop = {

      val running = BloopRifle.check(config, bloopLogger)

      if (running) {
        scribe.info("Found a Bloop server running")
        Future.unit
      } else {
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
            case e: ConnectException => Left(e)
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

  // Needed for creating unique socket files for each bloop connection
  private[BloopServers] val connectionCounter = new AtomicInteger(0)

  private val bloopDirectories = {
    // Scala CLI is still used since we wanted to avoid having two separate servers
    ProjectDirectories.from(null, null, "ScalaCli")
  }

  lazy val bloopDaemonDir: AbsolutePath =
    bloopWorkingDir.resolve("daemon")

  lazy val bloopWorkingDir: AbsolutePath = {
    val baseDir =
      if (Properties.isMac) bloopDirectories.cacheDir
      else bloopDirectories.dataLocalDir
    AbsolutePath(Paths.get(baseDir).resolve("bloop"))
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
        .downloadDependency(coursierapi.Dependency.of(org, name, version))
        .map(_.toFile())
      Right(cp)
    } catch {
      case NonFatal(t) =>
        Left(t)
    }
  }

  def defaultBloopVersion = BloopRifleConfig.defaultVersion
}
