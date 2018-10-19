package scala.meta.internal.metals

import java.util
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.InitializeBuildParams
import java.util.Collections
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import scala.meta.internal.metals.BuildTool._
import scala.meta.io.AbsolutePath
import ProtocolConverters._
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DependencySourcesParams
import com.google.gson.JsonArray
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.collection.JavaConverters._

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
      .get(10L, TimeUnit.SECONDS)
    server.onBuildInitialized()
  }
  def allWorkspaceIds(): util.List[BuildTargetIdentifier] = {
    server.workspaceBuildTargets().get().getTargets.map(_.getId)
  }
  def allDependencySources(): List[AbsolutePath] = {
    val ids = allWorkspaceIds()
    ids.forEach(id => id.setUri(URI.create(id.getUri).toString))
    val compileParams = new CompileParams(ids)
    compileParams.setArguments(new JsonArray)
    val compile = server.buildTargetCompile(compileParams).get()
    pprint.log(compile)
    val sources = server
      .buildTargetDependencySources(new DependencySourcesParams(ids))
      .get()
    val items = sources.getItems.asScala
    pprint.log(items)
    items.iterator
      .filter(_.getSources != null)
      .flatMap(_.getSources.asScala)
      .map(uri => AbsolutePath(Paths.get(URI.create(uri))))
      .toList
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
      case Bloop =>
        BloopServer.connect(workspace, languageClient, buildClient)
      case Sbt =>
        scribe.info("requesting to import project")
        val params = new ShowMessageRequestParams()
        params.setMessage(
          "sbt build detected, would you like to import the project via bloop?"
        )
        params.setType(MessageType.Info)
        params.setActions(
          List(
            new MessageActionItem("Import project via bloop")
          ).asJava
        )
        val response = languageClient.showMessageRequest(params).get()
        if (response != null) {
          BloopServer.connect(workspace, languageClient, buildClient)
        } else {
          None
        }
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
