package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.{lsp4j => l}

/**
 * A BSP client interface that uses lsp4j data structures where applicable.
 *
 * Does not extend bsp4j BuildClient to avoid unnecessary converting between
 * identical bsp/lsp data structures and also ignore unused endpoints like
 * build/registerFileWatcher.
 */
trait MetalsBuildClient {

  @JsonNotification("build/showMessage")
  def onBuildShowMessage(params: l.MessageParams): Unit

  @JsonNotification("build/logMessage")
  def onBuildLogMessage(params: l.MessageParams): Unit

  @JsonNotification("build/publishDiagnostics")
  def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit

  @JsonNotification("buildTarget/didChange")
  def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit

  @JsonNotification("buildTarget/compileReport")
  def onBuildTargetCompileReport(params: b.CompileReport): Unit

  def onConnect(remoteServer: BuildServer): Unit

}
