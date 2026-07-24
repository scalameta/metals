package scala.meta.internal.metals.mbt

import java.io.PipedInputStream
import java.io.PipedOutputStream

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.buildserver.BuildServerConnectionFactory
import scala.meta.internal.metals.buildserver.SocketConnection
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildClient
import org.eclipse.lsp4j.jsonrpc.Launcher

abstract class MbtBuildServerConnectionFactory(
    workspace: AbsolutePath,
    buildClient: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
    config: MetalsServerConfig,
    requestTimeOutNotification: DismissedNotifications#Notification,
    reconnectNotification: DismissedNotifications#Notification,
    bspStatusOpt: Option[ConnectionBspStatus],
    workDoneProgress: WorkDoneProgress,
    scalaVersionSelector: ScalaVersionSelector,
    debugStarter: Option[MbtDebugSessionStarter] = None,
) extends BuildServerConnectionFactory(
      projectRoot = workspace,
      bspTraceRoot = workspace,
      localClient = buildClient,
      languageClient = languageClient,
      requestTimeOutNotification = requestTimeOutNotification,
      reconnectNotification = reconnectNotification,
      config = config,
      serverName = "MBT",
      bspStatusOpt = bspStatusOpt,
      supportsWrappedSources = None,
      workDoneProgress = workDoneProgress,
    ) {

  protected def mbtBuild(): MbtBuild

  override protected def connect()(implicit
      ec: ExecutionContextExecutorService
  ): Future[SocketConnection] = Future.successful {
    val clientInput = new PipedInputStream()
    val serverOutput = new PipedOutputStream(clientInput)
    val serverInput = new PipedInputStream()
    val clientOutput = new PipedOutputStream(serverInput)
    val finished = Promise[Unit]()
    def eagerBuild() = {
      val updatedBuild = mbtBuild()
      if (updatedBuild.isEmpty) MbtBuild.fromWorkspace(workspace)
      else updatedBuild
    }
    val server =
      new MbtBuildServer(
        workspace,
        eagerBuild,
        scalaVersionSelector,
        debugStarter,
      )
    val serverLauncher = new Launcher.Builder[BuildClient]()
      .setInput(serverInput)
      .setOutput(serverOutput)
      .setLocalService(server)
      .setRemoteInterface(classOf[BuildClient])
      .setExecutorService(ec)
      .create()
    server.onConnectWithClient(serverLauncher.getRemoteProxy)
    val listening = serverLauncher.startListening()
    Future {
      listening.get()
      ()
    }.onComplete(finished.tryComplete)
    val cancel = Cancelable { () =>
      listening.cancel(false)
      clientOutput.close()
      clientInput.close()
      serverInput.close()
      serverOutput.close()
      finished.trySuccess(())
    }
    SocketConnection(
      serverName,
      new ClosableOutputStream(clientOutput, s"$serverName output stream"),
      new QuietInputStream(clientInput, s"$serverName input stream"),
      List(cancel),
      finished,
    )
  }

}
