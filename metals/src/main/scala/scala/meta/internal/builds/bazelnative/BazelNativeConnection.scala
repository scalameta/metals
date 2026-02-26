package scala.meta.internal.builds.bazelnative

import java.io.PipedInputStream
import java.io.PipedOutputStream

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.BazelNativeBuildTool
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.jsonrpc.Launcher

/**
 * Creates the in-process BSP connection between `BuildServerConnection`
 * and `BazelNativeBspServer`.
 *
 * Uses piped streams for JSON-RPC communication within the same JVM.
 * Routes through `BuildServerConnection.fromSockets` so that the
 * `ServerLivenessMonitor` is properly wired.
 */
object BazelNativeConnection {

  def establish(
      workspace: AbsolutePath,
      localClient: MetalsBuildClient,
      languageClient: ConfiguredLanguageClient,
      requestTimeOutNotification: DismissedNotifications#Notification,
      reconnectNotification: DismissedNotifications#Notification,
      config: MetalsServerConfig,
      userConfiguration: () => UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
      workDoneProgress: WorkDoneProgress,
  )(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {

    val serverName = BazelNativeBuildTool.bspName

    val process = new BazelNativeProcess(workspace, userConfiguration)

    def createConnection(): Future[SocketConnection] = Future {
      val translator = new BazelNativeBepTranslator(null)
      val besServer = new BazelNativeBesServer(translator)

      val bspServer =
        new BazelNativeBspServer(workspace, process, besServer, translator)

      // Wire the translator to push events to the BSP server
      val wiredTranslator = new BazelNativeBepTranslator(bspServer)
      val wiredBesServer = new BazelNativeBesServer(wiredTranslator)

      val wiredBspServer =
        new BazelNativeBspServer(
          workspace,
          process,
          wiredBesServer,
          wiredTranslator,
        )

      val besPort = wiredBesServer.start()
      scribe.debug(
        s"[BazelNative Connection] BES server started on port $besPort"
      )

      // Set up piped streams for JSON-RPC communication
      // Client writes to serverInput, reads from clientInput
      val serverInput = new PipedInputStream(65536)
      val clientOutput = new PipedOutputStream(serverInput)
      val clientInput = new PipedInputStream(65536)
      val serverOutput = new PipedOutputStream(clientInput)

      // Start JSON-RPC launcher for the BSP server side
      val serverLauncher =
        new Launcher.Builder[ch.epfl.scala.bsp4j.BuildClient]()
          .setLocalService(wiredBspServer)
          .setRemoteInterface(classOf[ch.epfl.scala.bsp4j.BuildClient])
          .setInput(serverInput)
          .setOutput(serverOutput)
          .setExecutorService(ec)
          .create()

      val remoteProxy = serverLauncher.getRemoteProxy
      wiredBspServer.setClient(remoteProxy)
      bspServer.setClient(remoteProxy)

      val listening = serverLauncher.startListening()

      val finishedPromise = Promise[Unit]()
      ec.execute(() =>
        try {
          listening.get()
          finishedPromise.trySuccess(())
        } catch {
          case _: Exception => finishedPromise.trySuccess(())
        }
      )

      val cancelable = Cancelable { () =>
        listening.cancel(true)
        wiredBesServer.shutdown()
        process.cancel()
      }

      SocketConnection(
        serverName,
        new ClosableOutputStream(clientOutput, "bazel-native-output"),
        clientInput,
        List(cancelable),
        finishedPromise,
      )
    }

    BuildServerConnection.fromSockets(
      projectRoot = workspace,
      bspTraceRoot = workspace,
      localClient = localClient,
      languageClient = languageClient,
      connect = () => createConnection(),
      requestTimeOutNotification = requestTimeOutNotification,
      reconnectNotification = reconnectNotification,
      config = config,
      userConfiguration = userConfiguration(),
      serverName = serverName,
      bspStatusOpt = bspStatusOpt,
      workDoneProgress = workDoneProgress,
    )
  }
}
