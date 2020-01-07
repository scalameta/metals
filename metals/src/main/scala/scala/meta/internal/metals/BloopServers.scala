package scala.meta.internal.metals

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.io.AbsolutePath
import bloop.launcher.LauncherMain
import java.nio.charset.StandardCharsets
import bloop.bloopgun.core.Shell
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.io.ByteArrayInputStream
import bloop.bloopgun.BloopgunCli
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
 * https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#bsp-connection-protocol
 */
final class BloopServers(
    sh: ScheduledExecutorService,
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    languageClient: LanguageClient
)(implicit ec: ExecutionContextExecutorService) {

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

  def newServer(): Future[Option[BuildServerConnection]] = {
    BuildServerConnection
      .fromStreams(
        workspace,
        client,
        languageClient,
        connectToLauncher
      )
      .map(Option(_))
  }

  private def connectToLauncher(): Future[SocketConnection] = {
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
        userNailgunPort = None,
        serverStarted
      )

    val job = ec.submit(new Runnable {
      override def run(): Unit = {
        launcher.runLauncher(
          BuildInfo.bloopVersion,
          skipBspConnection = false,
          Nil
        )
      }
    })

    serverStarted.future.map { _ =>
      SocketConnection(
        "Bloop",
        clientOut,
        clientIn,
        List(
          Cancelable { () =>
            clientOut.flush()
            clientOut.close()
          },
          Cancelable(() => job.cancel(true))
        )
      )
    }
  }
}
