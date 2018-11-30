package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
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
    initializeResult: InitializeBuildResult
)(implicit ec: ExecutionContext)
    extends Cancelable {

  private val ongoingRequests = new MutableCancelable().addAll(cancelables)

  /** Run build/shutdown procedure */
  def shutdown(): Future[Unit] = {
    for {
      _ <- server.buildShutdown().asScala
    } yield {
      server.onBuildExit()
      // Cancel pending compilations on our side, this is not needed for Bloop.
      cancel()
    }
  }

  private def register[T](e: CompletableFuture[T]): CompletableFuture[T] = {
    ongoingRequests.add(Cancelable(() => Try(e.cancel(true))))
    e
  }

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    register(server.buildTargetCompile(params))
  }

  private val cancelled = new AtomicBoolean(false)
  override def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      ongoingRequests.cancel()
    }
  }
}

object BuildServerConnection {

  /** Run build/initialize handshake */
  def initialize(
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
