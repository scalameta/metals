package scala.meta.internal.metals

import java.io.InputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import ch.epfl.scala.bsp4j._
import org.eclipse.lsp4j.jsonrpc.Launcher

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath
import scala.util.Try
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import MetalsEnrichments._
import java.io.IOException
import org.eclipse.lsp4j.services.LanguageClient

/**
 * An actively running and initialized BSP connection.
 */
case class BuildServerConnection(
    restablishConnection: () => Future[LauncherConnection],
    private val initialConnection: LauncherConnection,
    languageClient: LanguageClient
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  private val currentConnection = new AtomicReference(initialConnection)
  private var awaitingConnection: AtomicReference[Promise[LauncherConnection]] =
    new AtomicReference()

  private val ongoingRequests =
    new MutableCancelable().addAll(initialConnection.cancelables)

  def version = currentConnection.get.version

  def name = currentConnection.get.socketConnection.serverName

  /** Run build/shutdown procedure */
  def shutdown(): Future[Unit] = Future {
    try {
      currentConnection.get.server.buildShutdown().get(2, TimeUnit.SECONDS)
      currentConnection.get.server.onBuildExit()
      // Cancel pending compilations on our side, this is not needed for Bloop.
      cancel()
    } catch {
      case e: TimeoutException =>
        scribe.error(
          s"timeout: build server '${currentConnection.get.displayName}' during shutdown"
        )
      case InterruptException() =>
      case e: Throwable =>
        scribe.error(
          s"build shutdown: ${currentConnection.get.displayName}",
          e
        )
    }
  }

  private def reconnect() = synchronized {
    Option(awaitingConnection.get) match {
      case Some(promise) => promise.future
      case None =>
        val params = Messages.DisconnectedServer.params()
        languageClient.showMessageRequest(params).asScala.flatMap {
          case response if response == Messages.DisconnectedServer.reconnect =>
            val newPromise = Promise[LauncherConnection]()
            awaitingConnection.set(newPromise)
            val reconnect = restablishConnection().map { launcherConnection =>
              currentConnection.set(launcherConnection)
              awaitingConnection.set(null)
              ongoingRequests.addAll(launcherConnection.cancelables)
              launcherConnection
            }
            newPromise.completeWith(reconnect)
            newPromise.future
          case _ => throw new InterruptedException
        }

    }
  }

  private def register[T](
      action: MetalsBuildServer => CompletableFuture[T]
  ): CompletableFuture[T] = {
    val connect = if (currentConnection.get.socketConnection.isClosed) {
      reconnect()
    } else {
      Future.successful(currentConnection.get)
    }
    val future = connect
      .flatMap { launcherConnection =>
        val e = action(launcherConnection.server)
        ongoingRequests.add(
          Cancelable(() =>
            Try(e.completeExceptionally(new InterruptedException()))
          )
        )
        e.asScala
      }
      .recoverWith {
        case _: IOException =>
          reconnect().flatMap(conn => action(conn.server).asScala)
      }
    CancelTokens.future(_ => future)
  }

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    register { server =>
      server.buildTargetCompile(params)
    }
  }

  def mainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] = {
    register(server => server.buildTargetScalaMainClasses(params))
  }

  def testClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] = {
    register(server => server.buildTargetScalaTestClasses(params))
  }

  def startDebugSession(params: DebugSessionParams): CompletableFuture[URI] = {
    register(server =>
      server
        .startDebugSession(params)
        .thenApply(address => URI.create(address.getUri))
    )
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

}

object BuildServerConnection {

  /**
   * Establishes a new build server connection with the given input/output streams.
   *
   * This method is blocking, doesn't return Future[], because if the `initialize` handshake
   * doesn't complete within a few seconds then something is wrong. We want to fail fast
   * when initialization is not successful.
   */
  def fromStreams(
      workspace: AbsolutePath,
      localClient: MetalsBuildClient,
      languageClient: LanguageClient,
      setupConnection: () => Future[SocketConnection]
  )(
      implicit ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {

    def setupServer(): Future[LauncherConnection] = {
      setupConnection().map {
        case conn @ SocketConnection(name, output, input, cancelables) =>
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
      BuildServerConnection(
        setupServer,
        connection,
        languageClient
      )
    }
  }

  final case class BloopExtraBuildParams(
      semanticdbVersion: String,
      supportedScalaVersions: java.util.List[String]
  )

  /** Run build/initialize handshake */
  private def initialize(
      workspace: AbsolutePath,
      server: MetalsBuildServer
  ): InitializeBuildResult = {
    val extraParams = BloopExtraBuildParams(
      BuildInfo.scalametaVersion,
      BuildInfo.supportedScalaVersions.asJava
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
}

case class SocketConnection(
    serverName: String,
    ouput: ClosableOutputStream,
    input: InputStream,
    cancelables: List[Cancelable]
) {
  def isClosed = ouput.socketIsClosed
}

case class LauncherConnection(
    socketConnection: SocketConnection,
    server: MetalsBuildServer,
    displayName: String,
    cancelServer: Cancelable,
    version: String
) {
  def cancelables: List[Cancelable] =
    cancelServer :: socketConnection.cancelables
}
