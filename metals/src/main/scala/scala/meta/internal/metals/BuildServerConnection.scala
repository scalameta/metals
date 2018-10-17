package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import java.util.Collections
import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * An actively running BSP connection.
 */
case class BuildServerConnection(
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    server: MetalsBuildServer,
    cancelables: List[Cancelable]
)(implicit ec: ExecutionContext)
    extends Cancelable {

  private val ongoingRequests = new MutableCancelable().addAll(cancelables)

  /** Run build/initialize handshake */
  def initialize(): Future[Unit] = {
    for {
      _ <- server
        .buildInitialize(
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
        .asScala
    } yield {
      server.onBuildInitialized()
    }
  }

  /** Run build/shutdown procedure */
  def shutdown(): Future[Unit] = {
    for {
      _ <- server.buildShutdown().asScala
    } yield {
      server.onBuildExit()
      cancel()
    }
  }

  private def register[T](e: CompletableFuture[T]): CompletableFuture[T] = {
    ongoingRequests.add(Cancelable(() => e.cancel(true)))
    e
  }

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    register(server.buildTargetCompile(params))
  }

  override def cancel(): Unit = ongoingRequests.cancel()
}
