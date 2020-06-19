package scala.meta.internal.metals

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._
import com.google.gson.Gson
import org.eclipse.lsp4j.jsonrpc.JsonRpcException
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient

/**
 * An actively running and initialized BSP connection.
 */
class BuildServerConnection private (
    reestablishConnection: () => Future[
      BuildServerConnection.LauncherConnection
    ],
    initialConnection: BuildServerConnection.LauncherConnection,
    languageClient: LanguageClient,
    reconnectNotification: DismissedNotifications#Notification,
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  @volatile private var connection = Future.successful(initialConnection)
  initialConnection.onConnectionFinished(reconnect)

  private val isShuttingDown = new AtomicBoolean(false)
  private val onReconnection =
    new AtomicReference[BuildServerConnection => Future[Unit]](_ =>
      Future.successful(())
    )

  private val _version = new AtomicReference(initialConnection.version)

  private val ongoingRequests =
    new MutableCancelable().addAll(initialConnection.cancelables)

  def version: String = _version.get()

  // the name is set before when establishing conenction
  def name: String = initialConnection.socketConnection.serverName

  def onReconnection(
      index: BuildServerConnection => Future[Unit]
  ): Unit = {
    onReconnection.set(index)
  }

  /**
   * Run build/shutdown procedure */
  def shutdown(): Future[Unit] =
    connection.map { conn =>
      try {
        if (isShuttingDown.compareAndSet(false, true)) {
          conn.server.buildShutdown().get(2, TimeUnit.SECONDS)
          conn.server.onBuildExit()
          // Cancel pending compilations on our side, this is not needed for Bloop.
          cancel()
        }
      } catch {
        case e: TimeoutException =>
          scribe.error(
            s"timeout: build server '${conn.displayName}' during shutdown"
          )
        case InterruptException() =>
        case e: Throwable =>
          scribe.error(
            s"build shutdown: ${conn.displayName}",
            e
          )
      }
    }

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    register(server => server.buildTargetCompile(params))
  }

  def clean(params: CleanCacheParams): CompletableFuture[CleanCacheResult] = {
    register(server => server.buildTargetCleanCache(params))
  }

  def mainClasses(
      params: ScalaMainClassesParams
  ): Future[ScalaMainClassesResult] = {
    register(server => server.buildTargetScalaMainClasses(params)).asScala
  }

  def testClasses(
      params: ScalaTestClassesParams
  ): Future[ScalaTestClassesResult] = {
    register(server => server.buildTargetScalaTestClasses(params)).asScala
  }

  def startDebugSession(params: DebugSessionParams): Future[URI] = {
    register(server => server.startDebugSession(params)).asScala
      .map(address => URI.create(address.getUri))
  }

  def workspaceBuildTargets(): Future[WorkspaceBuildTargetsResult] = {
    register(server => server.workspaceBuildTargets()).asScala
  }

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): Future[ScalacOptionsResult] = {
    register(server => server.buildTargetScalacOptions(params)).asScala
  }

  def buildTargetSources(params: SourcesParams): Future[SourcesResult] = {
    register(server => server.buildTargetSources(params)).asScala
  }

  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): Future[DependencySourcesResult] = {
    register(server => server.buildTargetDependencySources(params)).asScala
  }

  private val cancelled = new AtomicBoolean(false)

  override def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      ongoingRequests.cancel()
    }
  }

  private def askUser(): Future[BuildServerConnection.LauncherConnection] = {
    if (config.askToReconnect) {
      if (!reconnectNotification.isDismissed) {
        val params = Messages.DisconnectedServer.params()
        languageClient.showMessageRequest(params).asScala.flatMap {
          case response if response == Messages.DisconnectedServer.reconnect =>
            reestablishConnection()
          case response if response == Messages.DisconnectedServer.notNow =>
            reconnectNotification.dismiss(5, TimeUnit.MINUTES)
            connection
          case _ =>
            connection
        }
      } else {
        connection
      }
    } else {
      reestablishConnection()
    }
  }

  private def reconnect(): Future[BuildServerConnection.LauncherConnection] = {
    val original = connection
    if (!isShuttingDown.get()) {
      synchronized {
        // if the future is different then the connection is already being reestablished
        if (connection eq original) {
          connection = askUser().map { conn =>
            // version can change when reconnecting
            _version.set(conn.version)
            ongoingRequests.addAll(conn.cancelables)
            conn.onConnectionFinished(reconnect)
            conn
          }
          connection.foreach(_ => onReconnection.get()(this))
        }
        connection
      }
    } else {
      connection
    }

  }
  private def register[T](
      action: MetalsBuildServer => CompletableFuture[T]
  ): CompletableFuture[T] = {
    val original = connection
    val actionFuture = original
      .flatMap { launcherConnection =>
        val resultFuture = action(launcherConnection.server)
        ongoingRequests.add(
          Cancelable(() =>
            Try(resultFuture.completeExceptionally(new InterruptedException()))
          )
        )
        resultFuture.asScala
      }
      .recoverWith {
        case io: JsonRpcException if io.getCause.isInstanceOf[IOException] =>
          synchronized {
            reconnect().flatMap(conn => action(conn.server).asScala)
          }
      }
    CancelTokens.future(_ => actionFuture)
  }

}

object BuildServerConnection {

  /**
   * Establishes a new build server connection with the given input/output streams.
   *
   * This method is blocking, doesn't return Future[], because if the `initialize` handshake
   * doesn't complete within a few seconds then something is wrong. We want to fail fast
   * when initialization is not successful.
   */
  def fromSockets(
      workspace: AbsolutePath,
      localClient: MetalsBuildClient,
      languageClient: LanguageClient,
      connect: () => Future[SocketConnection],
      reconnectNotification: DismissedNotifications#Notification,
      config: MetalsServerConfig
  )(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {

    def setupServer(): Future[LauncherConnection] = {
      connect().map {
        case conn @ SocketConnection(name, output, input, _, _) =>
          val tracePrinter = GlobalTrace.setupTracePrinter("BSP")
          val launcher = new Launcher.Builder[MetalsBuildServer]()
            .traceMessages(tracePrinter)
            .setOutput(output)
            .setInput(input)
            .setLocalService(localClient)
            .setRemoteInterface(classOf[MetalsBuildServer])
            .setExecutorService(ec)
            .create()
          val listening = launcher.startListening()
          val server = launcher.getRemoteProxy
          val result = BuildServerConnection.initialize(workspace, server)
          val stopListening =
            Cancelable(() => listening.cancel(false))
          LauncherConnection(
            conn,
            server,
            result.getDisplayName(),
            stopListening,
            result.getVersion()
          )
      }
    }

    setupServer().map { connection =>
      new BuildServerConnection(
        setupServer,
        connection,
        languageClient,
        reconnectNotification,
        config
      )
    }
  }

  final case class BloopExtraBuildParams(
      semanticdbVersion: String,
      supportedScalaVersions: java.util.List[String]
  )

  /**
   * Run build/initialize handshake */
  private def initialize(
      workspace: AbsolutePath,
      server: MetalsBuildServer
  ): InitializeBuildResult = {
    val extraParams = BloopExtraBuildParams(
      BuildInfo.scalametaVersion,
      BuildInfo.supportedScala2Versions.asJava
    )

    val initializeResult = server.buildInitialize {
      val params = new InitializeBuildParams(
        "Metals",
        BuildInfo.metalsVersion,
        BuildInfo.bspVersion,
        workspace.toURI.toString,
        new BuildClientCapabilities(
          Collections.singletonList("scala")
        )
      )
      val gson = new Gson
      val data = gson.toJsonTree(extraParams)
      params.setData(data)
      params
    }
    // Block on the `build/initialize` request because it should respond instantly
    // and we want to fail fast if the connection is not
    val result =
      try {
        initializeResult.get(5, TimeUnit.SECONDS)
      } catch {
        case e: TimeoutException =>
          scribe.error("Timeout waiting for 'build/initialize' response")
          throw e
      }
    server.onBuildInitialized()
    result
  }

  private case class LauncherConnection(
      socketConnection: SocketConnection,
      server: MetalsBuildServer,
      displayName: String,
      cancelServer: Cancelable,
      version: String
  ) {

    def cancelables: List[Cancelable] =
      cancelServer :: socketConnection.cancelables

    def onConnectionFinished(
        f: () => Unit
    )(implicit ec: ExecutionContext): Unit = {
      socketConnection.finishedPromise.future.foreach(_ => f())
    }
  }
}

case class SocketConnection(
    serverName: String,
    output: ClosableOutputStream,
    input: InputStream,
    cancelables: List[Cancelable],
    finishedPromise: Promise[Unit]
)
