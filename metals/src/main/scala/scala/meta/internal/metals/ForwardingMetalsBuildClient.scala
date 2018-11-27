package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.{lsp4j => l}

/**
 * A build client that forwards notifications from the build server to the language client.
 */
final class ForwardingMetalsBuildClient(
    languageClient: LanguageClient,
    diagnostics: Diagnostics
) extends MetalsBuildClient {

  private var buildServer: Option[b.BuildServer] = None
  def onBuildShowMessage(params: l.MessageParams): Unit =
    languageClient.showMessage(params)

  def onBuildLogMessage(params: l.MessageParams): Unit =
    params.getType match {
      case l.MessageType.Error =>
        scribe.error(params.getMessage)
      case l.MessageType.Warning =>
        scribe.warn(params.getMessage)
      case l.MessageType.Info =>
        scribe.info(params.getMessage)
      case l.MessageType.Log =>
        scribe.info(params.getMessage)
    }

  def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = {
    diagnostics.onBuildPublishDiagnostics(params)
  }

  def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = {
    scribe.info(params.toString)
  }

  def onBuildTargetCompileReport(params: b.CompileReport): Unit = {}

  def onConnect(server: b.BuildServer): Unit = {
    this.buildServer = Some(server)
  }

  // We ignore task{Start,Finish} notifications for now.
  @JsonNotification("build/taskStart")
  def buildTaskStart(params: TaskStartParams): Unit = {}
  @JsonNotification("build/taskFinish")
  def buildTaskEnd(params: TaskFinishParams): Unit = {}
}
