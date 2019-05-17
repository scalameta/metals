package scala.meta.internal.metals.eval

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.RestartArguments
import org.eclipse.lsp4j.debug.TerminateArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import scala.concurrent.ExecutionContext
import scala.meta.internal.eval.DebugAdapter
import scala.meta.internal.eval.JvmDebugAdapter
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.eval.MetalsDebugProtocol.LaunchParameters

/**
 * Metals receive requests without the full knowledge - e.g. without classpath.
 * Here, we provide such information
 */
class MetalsDebugAdapter(
    underlying: JvmDebugAdapter,
    parametersAdapter: DebugParametersAdapter
)(implicit ec: ExecutionContext)
    extends DebugAdapter {

  @JsonRequest
  def launch(params: LaunchParameters): CompletableFuture[Unit] = {
    parametersAdapter
      .adapt(params)
      .flatMap(underlying.launch(_).asScala)
      .asJava
  }

  @JsonRequest
  def initialize(
      args: InitializeRequestArguments
  ): CompletableFuture[Capabilities] =
    underlying.initialize(args)

  @JsonRequest
  def disconnect(args: DisconnectArguments): CompletableFuture[Unit] =
    underlying.disconnect(args)

  @JsonRequest
  def restart(params: RestartArguments): CompletableFuture[Unit] =
    underlying.restart(params)

  @JsonRequest
  def terminate(args: TerminateArguments): CompletableFuture[Unit] =
    underlying.terminate(args)

  override def setClient(client: IDebugProtocolClient): Unit =
    underlying.setClient(client)

  override def shutdown(): Unit =
    underlying.shutdown()
}

object MetalsDebugAdapter {
  def apply(compilations: Compilations, buildTargets: BuildTargets)(
      implicit ec: ExecutionContext
  ): MetalsDebugAdapter = {
    val parametersAdapter =
      new DebugParametersAdapter(compilations, buildTargets)

    new MetalsDebugAdapter(new JvmDebugAdapter, parametersAdapter)
  }
}
