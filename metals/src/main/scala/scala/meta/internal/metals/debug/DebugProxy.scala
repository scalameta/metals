package scala.meta.internal.metals.debug

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.GlobalTrace
import scala.meta.internal.metals.debug.DebugProtocol.OutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.RestartRequest
import scala.meta.internal.metals.debug.DebugProxy._

private[debug] final class DebugProxy(
    sessionName: String,
    client: RemoteEndpoint,
    server: RemoteEndpoint
)(implicit ec: ExecutionContext) {
  private val exitStatus = Promise[ExitStatus]()
  @volatile private var outputTerminated = false
  private val cancelled = new AtomicBoolean()

  lazy val listen: Future[ExitStatus] = {
    scribe.info(s"Starting debug proxy for [$sessionName]")
    listenToServer()
    listenToClient()

    exitStatus.future
  }

  private def listenToClient(): Unit = {
    Future(client.listen(handleClientMessage)).andThen { case _ => cancel() }
  }

  private def listenToServer(): Unit = {
    Future(server.listen(handleServerMessage)).andThen { case _ => cancel() }
  }

  private val handleClientMessage: MessageConsumer = {
    case _ if cancelled.get() =>
    // ignore
    case RestartRequest(message) =>
      // set the status first, since the server can kill the connection
      exitStatus.trySuccess(Restarted)
      outputTerminated = true
      server.consume(message)
    case null =>
      () // do nothing
    case message =>
      server.consume(message)
  }

  private val handleServerMessage: MessageConsumer = {
    case _ if cancelled.get() =>
    // ignore
    case OutputNotification() if outputTerminated =>
    // ignore. When restarting, the output keeps getting printed for a short while after the
    // output window gets refreshed resulting in stale messages being printed on top, before
    // any actual logs from the restarted process

    case message =>
      client.consume(message)
  }

  def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      scribe.info(s"Canceling debug proxy for [$sessionName]")
      exitStatus.trySuccess(Terminated)
      Cancelable.cancelAll(List(client, server))
    }
  }
}

private[debug] object DebugProxy {
  sealed trait ExitStatus
  case object Terminated extends ExitStatus
  case object Restarted extends ExitStatus

  def open(
      name: String,
      awaitClient: () => Future[Socket],
      connectToServer: () => Future[Socket]
  )(implicit ec: ExecutionContext): Future[DebugProxy] = {
    for {
      server <- connectToServer()
        .map(new SocketEndpoint(_))
        .map(endpoint => withLogger(endpoint, "dap-server"))
      client <- awaitClient()
        .map(new SocketEndpoint(_))
        .map(endpoint => withLogger(endpoint, "dap-client"))
    } yield new DebugProxy(name, client, server)
  }

  private def withLogger(
      endpoint: RemoteEndpoint,
      name: String
  ): RemoteEndpoint = {
    new EndpointLogger(endpoint, GlobalTrace.setup(name))
  }
}
