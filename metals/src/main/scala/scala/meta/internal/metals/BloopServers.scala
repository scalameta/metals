package scala.meta.internal.metals

import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.StandardCharsets

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import bloop.bloopgun.BloopgunCli
import bloop.bloopgun.core.Shell
import bloop.launcher.LauncherMain
import org.eclipse.lsp4j.services.LanguageClient

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
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    languageClient: LanguageClient,
    tables: Tables,
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService) {

  private val bloop = "Bloop"

  def shutdownServer(): Boolean = {
    val dummyIn = new ByteArrayInputStream(new Array(0))
    val cli = new BloopgunCli(
      BuildInfo.bloopVersion,
      dummyIn,
      System.out,
      System.err,
      Shell.default
    )
    val result = cli.run(Array("exit")) == 0
    if (!result) {
      scribe.warn("There were issues stopping the Bloop server.")
      scribe.warn(
        "If it doesn't start back up you can run the `build-restart` command manually."
      )
    }
    result
  }

  def newServer(
      workspace: AbsolutePath,
      userConfiguration: UserConfiguration
  ): Future[BuildServerConnection] = {
    val bloopVersion = userConfiguration.currentBloopVersion
    BuildServerConnection
      .fromSockets(
        workspace,
        client,
        languageClient,
        () => connectToLauncher(bloopVersion, config.bloopPort),
        tables.dismissedNotifications.ReconnectBsp,
        config,
        bloop
      )
  }

  /**
   * Ensure Bloop is running the inteded version that the user has passed
   * in via UserConfiguration. If not, shut Bloop down and reconnect to it.
   *
   * @param expectedVersion desired version that the user has passed in. This
   *                        could either be a newly passed in version from the
   *                            user or the default Bloop version.
   * @param runningVersion the current running version of Bloop.
   * @param userDefinedNew whether or not the user has defined a new version.
   * @param userDefinedOld whether or not the user has the version running
   *                       defined or if they are just running the default.
   * @param reconnect      function to connect back to the build server.
   */
  def ensureDesiredVersion(
      expectedVersion: String,
      runningVersion: String,
      userDefinedNew: Boolean,
      userDefinedOld: Boolean,
      reconnect: () => Future[BuildChange]
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
            shutdownServer()
            reconnect().ignoreValue
          case _ =>
            Future.successful(())
        }
    } else {
      Future.successful(())
    }
  }

  private def connectToLauncher(
      bloopVersion: String,
      bloopPort: Option[Int]
  ): Future[SocketConnection] = {
    val launcherInOutPipe = Pipe.open()
    val launcherIn = new QuietInputStream(
      Channels.newInputStream(launcherInOutPipe.source()),
      "Bloop InputStream"
    )
    val clientOut = new ClosableOutputStream(
      Channels.newOutputStream(launcherInOutPipe.sink()),
      "Bloop OutputStream"
    )

    val clientInOutPipe = Pipe.open()
    val clientIn = Channels.newInputStream(clientInOutPipe.source())
    val launcherOut = Channels.newOutputStream(clientInOutPipe.sink())

    val serverStarted = Promise[Unit]()
    val launcher =
      new LauncherMain(
        launcherIn,
        launcherOut,
        System.err,
        StandardCharsets.UTF_8,
        Shell.default,
        userNailgunHost = None,
        userNailgunPort = bloopPort,
        serverStarted
      )

    val finished = Promise[Unit]()
    val job = ec.submit(new Runnable {
      override def run(): Unit = {
        launcher.runLauncher(
          bloopVersion,
          skipBspConnection = false,
          Nil
        )
        finished.success(())
      }
    })

    serverStarted.future.map { _ =>
      SocketConnection(
        bloop,
        clientOut,
        clientIn,
        List(
          Cancelable { () =>
            clientOut.flush()
            clientOut.close()
          },
          Cancelable(() => job.cancel(true))
        ),
        finished
      )
    }
  }
}
