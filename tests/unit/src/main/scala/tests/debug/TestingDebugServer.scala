package tests.debug

import java.nio.file.Paths
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.eval.DebugAdapter
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.eval.MetalsDebugAdapter
import scala.meta.internal.metals.eval.MetalsDebugProtocol._
import scala.meta.io.AbsolutePath

final class TestingDebugServer(
    workspace: AbsolutePath,
    server: MetalsDebugAdapter
)(implicit ec: ExecutionContext)
    extends DebugAdapter {

  def initialize(
      arguments: InitializeRequestArguments
  ): Future[Capabilities] = {
    server.initialize(arguments).asScala
  }

  /**
   * Assumption: class to be run and file have the same name
   */
  def launch(file: String): Future[Unit] = {
    val main = Paths.get(file).getFileName.toString.dropRight(".scala".length)
    val moduleName = Paths.get(file).getName(0).toString
    val target = workspace.resolve(s"$moduleName")
    val buildTargetURI = s"file:$target/?id=$moduleName"

    val parameters = LaunchParameters(workspace.toString, main, buildTargetURI)
    server.launch(parameters).asScala
  }

  def disconnect(): Future[Unit] = {
    server.disconnect(new DisconnectArguments()).asScala
  }

  override def setClient(client: IDebugProtocolClient): Unit =
    server.setClient(client)

  override def shutdown(): Unit =
    server.shutdown()
}

object TestingDebugServer {
  def apply(
      workspace: AbsolutePath,
      compilations: Compilations,
      buildTargets: BuildTargets
  )(implicit ec: ExecutionContext): TestingDebugServer = {
    val metalsAdapter = MetalsDebugAdapter(compilations, buildTargets)
    new TestingDebugServer(workspace, metalsAdapter)
  }
}
