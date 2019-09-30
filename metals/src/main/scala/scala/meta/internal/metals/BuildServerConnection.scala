package scala.meta.internal.metals

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import ch.epfl.scala.bsp4j._
import org.eclipse.lsp4j.jsonrpc.Launcher

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath
import scala.util.Try

/**
 * An actively running and initialized BSP connection.
 */
case class BuildServerConnection(
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    server: MetalsBuildServer,
    cancelables: List[Cancelable],
    initializeResult: InitializeBuildResult,
    name: String
)(implicit ec: ExecutionContext)
    extends Cancelable {

  private val ongoingRequests = new MutableCancelable().addAll(cancelables)

  /** Run build/shutdown procedure */
  def shutdown(): Future[Unit] = Future {
    try {
      server.buildShutdown().get(2, TimeUnit.SECONDS)
      server.onBuildExit()
      // Cancel pending compilations on our side, this is not needed for Bloop.
      cancel()
    } catch {
      case e: TimeoutException =>
        scribe.error(
          s"timeout: build server '${initializeResult.getDisplayName}' during shutdown"
        )
      case InterruptException() =>
      case e: Throwable =>
        scribe.error(
          s"build shutdown: ${initializeResult.getDisplayName()}",
          e
        )
    }
  }

  private def register[T](e: CompletableFuture[T]): CompletableFuture[T] = {
    ongoingRequests.add(
      Cancelable(
        () => Try(e.completeExceptionally(new InterruptedException()))
      )
    )
    e
  }

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    register(server.buildTargetCompile(params))
  }

  def mainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] = {
    register(server.buildTargetScalaMainClasses(params))
  }

  def testClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] = {
    register(server.buildTargetScalaTestClasses(params))
  }

  def startDebugSession(params: DebugSessionParams): CompletableFuture[URI] = {
    register(
      server
        .startDebugSession(params)
        .thenApply(address => URI.create(address.getUri))
    )
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
      output: OutputStream,
      input: InputStream,
      onShutdown: List[Cancelable],
      name: String
  )(implicit ec: ExecutionContextExecutorService): BuildServerConnection = {
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
    BuildServerConnection(
      workspace,
      localClient,
      server,
      stopListening :: onShutdown,
      result,
      name
    )
  }

  /** Run build/initialize handshake */
  private def initialize(
      workspace: AbsolutePath,
      server: MetalsBuildServer
  ): InitializeBuildResult = {
    val initializeResult = server.buildInitialize(
      new InitializeBuildParams(
        "Metals",
        BuildInfo.metalsVersion,
        BuildInfo.bspVersion,
        workspace.toURI.toString,
        new BuildClientCapabilities(
          Collections.singletonList("scala")
        )
      )
    )
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
