package scala.meta.internal.metals.bloop

import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Paths
import java.util.concurrent.ScheduledExecutorService

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.ConnectionProvider
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.Interruptable.MetalsCancelException
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.OldBloopVersionRunning
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.buildserver.BuildServerConnectionFactory
import scala.meta.internal.metals.buildserver.SocketConnection
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BspConnection

abstract class BloopServerConnectionFactory(
    projectRoot: AbsolutePath,
    bspTraceRoot: AbsolutePath,
    client: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
    requestTimeOutNotification: DismissedNotifications#Notification,
    reconnectNotification: DismissedNotifications#Notification,
    serverConfig: MetalsServerConfig,
    bspStatusOpt: Option[ConnectionBspStatus],
    workDoneProgress: WorkDoneProgress,
    bloopLogger: BloopRifleLogger,
    sh: ScheduledExecutorService,
    bloopWorkingDir: AbsolutePath,
) extends BuildServerConnectionFactory(
      projectRoot,
      bspTraceRoot,
      client,
      languageClient,
      requestTimeOutNotification,
      reconnectNotification,
      serverConfig,
      "Bloop",
      bspStatusOpt,
      supportsWrappedSources = None,
      workDoneProgress = workDoneProgress,
    ) {

  private def metalsJavaHome = sys.props
    .get("java.home")
    .orElse(sys.env.get("JAVA_HOME"))

  protected def bloopConfig(
      userConfig: Option[UserConfiguration],
      projectRoot: Option[AbsolutePath],
  ): BloopRifleConfig

  override protected def connect()(implicit
      ec: ExecutionContextExecutorService
  ): Future[SocketConnection] = {
    // User configuration is mutable, so we retrieve it just once to stay consistent
    val userConfigurationSnapshot = userConfiguration()
    val config = bloopConfig(Some(userConfigurationSnapshot), Some(projectRoot))

    val maybeStartBloop = {

      val running = BloopRifle.check(config, bloopLogger)

      if (running) {
        scribe.debug("Found a Bloop server running")
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
          userConfigurationSnapshot.bloopVersion.getOrElse(
            BloopServers.defaultBloopVersion
          )
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
        serverName,
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

  // Copied from bloop-rifle
  // See https://github.com/scalacenter/bloop/blob/cb2578b7a45724f0ba9d51d2044e0bddbbae7150/bloop-rifle/src/main/scala/bloop/rifle/BloopServer.scala#L119
  private def openConnection(
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

  /* Added after 1.3.4, we can probably remove this in a future version.
   */
  private def checkOldBloopRunning()(implicit
      ec: ExecutionContext
  ): Future[Unit] = try {
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

}
