package scala.meta.internal.metals.debug

import java.net.Socket
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.debug.DebugProtocol.OutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.RestartRequest
import Proxy._

private[debug] final class Proxy(client: RemoteEndpoint, server: RemoteEndpoint)(
    implicit ec: ExecutionContext
) {
  private val exitStatus = Promise[ExitStatus]()
  @volatile private var outputTerminated = false

  lazy val listen: Future[ExitStatus] = {
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

  private def handleClientMessage: MessageConsumer = {
    case RestartRequest(message) =>
      // set the status first, since the server can kill the connection
      exitStatus.trySuccess(Restarted)
      outputTerminated = true
      server.consume(message)

    case message =>
      server.consume(message)
  }

  private val handleServerMessage: MessageConsumer = {
    case OutputNotification() if outputTerminated =>
    // ignore. When restarting, the output keeps getting printed for a short while after the
    // output window gets refreshed resulting in stale messages being printed on top, before
    // any actual logs from the restarted process

    case message =>
      client.consume(message)
  }

  def cancel(): Unit = {
    exitStatus.trySuccess(Terminated)
    Cancelable.cancelAll(client, server)
  }
}

private[debug] object Proxy {
  import scala.meta.internal.metals.MetalsEnrichments._

  sealed trait ExitStatus
  case object Terminated extends ExitStatus
  case object Restarted extends ExitStatus

  def open(
      awaitClient: () => Future[Socket],
      connectToServer: () => Future[Socket]
  )(implicit ec: ExecutionContext): Future[Proxy] = {
    for {
      client <- awaitClient()
        .map(new RemoteEndpoint(_))
        .withTimeout(10, TimeUnit.SECONDS)
      server <- connectToServer()
        .map(new RemoteEndpoint(_))
        .withTimeout(10, TimeUnit.SECONDS)
    } yield new Proxy(client, server)
  }
}
