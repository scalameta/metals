package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.InitializeBuildParams
import java.util.Collections
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import scala.meta.internal.metals.BuildTool._
import scala.meta.io.AbsolutePath

case class BuildServerConnection(
    client: MetalsBuildClient,
    server: MetalsBuildServer,
    cancelables: List[Cancelable]
) extends Cancelable {
  def initialize(workspace: AbsolutePath): Unit = {
    server
      .buildInitialize(
        new InitializeBuildParams(
          workspace.toURI.toString,
          new BuildClientCapabilities(Collections.singletonList("scala"), false)
        )
      )
      .get()
    server.onBuildInitialized()
  }
  override def cancel(): Unit = Cancelable.cancelAll(cancelables)
}

object BuildServerConnection {

  def connect(
      workspace: AbsolutePath,
      languageClient: LanguageClient,
      buildClient: MetalsBuildClient
  ): Option[BuildServerConnection] = {
    BuildTool.autoDetect(workspace) match {
      case Bloop | Sbt =>
        BloopServer.connect(workspace, languageClient, buildClient)
      case Unknown =>
        languageClient.showMessage(
          new MessageParams(
            MessageType.Warning,
            "Unable to auto-detect build tool, code navigation will not work."
          )
        )
        None
      case unsupported =>
        val name = unsupported.productPrefix.toLowerCase()
        languageClient.showMessage(
          new MessageParams(
            MessageType.Warning,
            s"Detected unsupported build tool $name. " +
              s"Code navigation will not function. " +
              s"Please open a ticket to discuss how to add support for $name."
          )
        )
        None
    }
  }
}
