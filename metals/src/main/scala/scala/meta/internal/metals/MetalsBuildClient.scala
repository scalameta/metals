package scala.meta.internal.metals

import scala.concurrent.duration.FiniteDuration

import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

/**
 * A BSP client interface that uses lsp4j data structures where applicable.
 *
 * Does not extend bsp4j BuildClient to avoid unnecessary converting between
 * identical bsp/lsp data structures and also ignore unused endpoints like
 * build/registerFileWatcher.
 */
trait MetalsBuildClient {

  @JsonNotification("build/showMessage")
  def onBuildShowMessage(params: b.ShowMessageParams): Unit

  @JsonNotification("build/logMessage")
  def onBuildLogMessage(params: b.LogMessageParams): Unit

  @JsonNotification("build/publishDiagnostics")
  def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit

  @JsonNotification("buildTarget/didChange")
  def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit

  @JsonNotification("buildTarget/compileReport")
  def onBuildTargetCompileReport(params: b.CompileReport): Unit

  def buildHasErrors(buildTargetId: b.BuildTargetIdentifier): Boolean

  def buildHasErrors(file: AbsolutePath): Boolean

  def buildHasErrors: Boolean

  /**
   * Called when the connection to the given build server is closed or lost.
   * Ends progress of compilations started by that server, which would
   * otherwise never receive `build/taskFinish` (see scalameta/metals#3464).
   */
  def onConnectionClosed(server: BuildServerConnection): Unit

  /**
   * Called when a request that may have triggered a compilation (a
   * `buildTarget/compile`, but also a `buildTarget/run`) reached a terminal
   * state. Ends progress of compilations the server started under `originId`
   * but never finished with `build/taskFinish` (see scalameta/metals#3464).
   */
  def onCompileRequestFinished(
      originId: String,
      targets: Seq[b.BuildTargetIdentifier],
  ): Unit

  /**
   * Bounded-liveness cleanup for compilations triggered by a request without
   * a usable terminal boundary, e.g. `buildTarget/run` while the launched
   * process keeps running (see scalameta/metals#3464).
   */
  def endIdleCompilations(
      originId: String,
      targets: Seq[b.BuildTargetIdentifier],
      maxIdle: FiniteDuration,
  ): Unit
}
