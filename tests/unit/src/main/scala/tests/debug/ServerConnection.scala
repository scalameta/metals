package tests.debug

import java.net.Socket

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import tests.debug.RemoteConnection._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.GlobalTrace

final class ServerConnection(
    val connection: CancelableFuture[Unit],
    val server: IDebugProtocolServer
)(implicit val ec: ExecutionContext)
    extends RemoteConnection
    with ServerProxy

object ServerConnection {
  def open(socket: Socket, service: IDebugProtocolClient)(
      implicit es: ExecutionContextExecutorService
  ): ServerConnection = {
    val launcher = RemoteConnection
      .builder[IDebugProtocolServer](socket, service)
      .traceMessages(GlobalTrace.setup("dap-server"))
      .create()

    val connection = start(launcher, socket)
    new ServerConnection(connection, launcher.getRemoteProxy)
  }
}
