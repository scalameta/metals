package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.ProtocolConverters._

// NOTE: does not extend b.BuildClient to avoid unnecessary converting between
// identical bsp/lsp data structures and also ignore unused endpoints like
// build/registerFileWatcher.
class MetalsBuildClient(languageClient: LanguageClient) {
  private var buildServer: Option[b.BuildServer] = None

  @JsonNotification("build/showMessage")
  def onBuildShowMessage(params: l.MessageParams): Unit =
    languageClient.showMessage(params)

  @JsonNotification("build/logMessage")
  def onBuildLogMessage(params: l.MessageParams): Unit =
    languageClient.logMessage(params)

  @JsonNotification("build/publishDiagnostics")
  def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = {
    languageClient.publishDiagnostics(
      new l.PublishDiagnosticsParams(
        params.getUri,
        params.getDiagnostics.map(_.toLSP)
      )
    )
    scribe.info(params.toString)
  }

  @JsonNotification("buildTarget/didChange")
  def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = {
    scribe.info(params.toString)
  }

  @JsonNotification("buildTarget/compileReport")
  def onBuildTargetCompileReport(params: b.CompileReport): Unit = {
    scribe.info(params.toString)
  }

  def onConnect(server: b.BuildServer): Unit = {
    this.buildServer = Some(server)
  }
}

