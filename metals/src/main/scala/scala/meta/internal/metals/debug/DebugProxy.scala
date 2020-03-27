package scala.meta.internal.metals.debug

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.GlobalTrace
import scala.meta.internal.metals.debug.DebugProtocol.InitializeRequest
import scala.meta.internal.metals.debug.DebugProtocol.LaunchRequest
import scala.meta.internal.metals.debug.DebugProtocol.OutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.RestartRequest
import scala.meta.internal.metals.debug.DebugProtocol.SetBreakpointRequest
import scala.meta.internal.metals.debug.DebugProxy._

private[debug] final class DebugProxy(
    sessionName: String,
    sourcePathProvider: SourcePathProvider,
    client: RemoteEndpoint,
    server: ServerAdapter
)(implicit ec: ExecutionContext) {
  private val exitStatus = Promise[ExitStatus]()
  @volatile private var outputTerminated = false
  @volatile private var debugMode: DebugMode = DebugMode.Enabled

  private val cancelled = new AtomicBoolean()
  private val adapters = new MetalsDebugAdapters

  private val handleSetBreakpointsRequest =
    new SetBreakpointsRequestHandler(server, adapters)

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
    Future(server.onReceived(handleServerMessage))
      .andThen { case _ => cancel() }
  }

  private val handleClientMessage: MessageConsumer = {
    case null =>
      () // ignore
    case _ if cancelled.get() =>
      () // ignore
    case request @ InitializeRequest(args) =>
      adapters.initialize(args)
      server.send(request)
    case request @ LaunchRequest(debugMode) =>
      this.debugMode = debugMode
      server.send(request)
    case request @ RestartRequest(_) =>
      // set the status first, since the server can kill the connection
      exitStatus.trySuccess(Restarted)
      outputTerminated = true
      server.send(request)
    case request @ SetBreakpointRequest(_) if debugMode == DebugMode.Disabled =>
      // ignore breakpoints when not debugging
      val response = new SetBreakpointsResponse
      response.setBreakpoints(Array.empty)
      client.consume(DebugProtocol.syntheticResponse(request, response))
    case request @ SetBreakpointRequest(args) =>
      handleSetBreakpointsRequest(args)
        .map(DebugProtocol.syntheticResponse(request, _))
        .foreach(client.consume)

    case message =>
      server.send(message)
  }

  private val handleServerMessage: Message => Unit = {
    case null =>
      () // ignore
    case _ if cancelled.get() =>
      () // ignore
    case OutputNotification() if outputTerminated =>
    // ignore. When restarting, the output keeps getting printed for a short while after the
    // output window gets refreshed resulting in stale messages being printed on top, before
    // any actual logs from the restarted process
    case response @ DebugProtocol.StackTraceResponse(args) =>
      import scala.meta.internal.metals.JsonParser._
      args.getStackFrames.foreach {
        case frame if frame.getSource == null =>
        // send as is, it is most often a frame for a synthetic class
        case frame =>
          sourcePathProvider.findPathFor(frame.getSource) match {
            case Some(path) =>
              frame.getSource.setPath(adapters.adaptPathForClient(path))
            case None =>
              // don't send invalid source if we couldn't adapt it
              frame.setSource(null)
          }
      }
      response.setResult(args.toJson)
      client.consume(response)
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

  trait DebugMode
  object DebugMode {
    case object Enabled extends DebugMode
    case object Disabled extends DebugMode
  }

  def open(
      name: String,
      sourcePathProvider: SourcePathProvider,
      awaitClient: () => Future[Socket],
      connectToServer: () => Future[Socket]
  )(implicit ec: ExecutionContext): Future[DebugProxy] = {
    for {
      server <- connectToServer()
        .map(new SocketEndpoint(_))
        .map(endpoint => withLogger(endpoint, "dap-server"))
        .map(new MessageIdAdapter(_))
        .map(new ServerAdapter(_))
      client <- awaitClient()
        .map(new SocketEndpoint(_))
        .map(endpoint => withLogger(endpoint, "dap-client"))
        .map(new MessageIdAdapter(_))
    } yield new DebugProxy(name, sourcePathProvider, client, server)
  }

  private def withLogger(
      endpoint: RemoteEndpoint,
      name: String
  ): RemoteEndpoint = {
    val trace = GlobalTrace.setupTracePrinter(name)
    if (trace == null) endpoint
    else new EndpointLogger(endpoint, trace)
  }
}
