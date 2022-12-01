package scala.meta.internal.metals.debug

import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SourceMapper
import scala.meta.internal.metals.StacktraceAnalyzer
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.debug.DebugProtocol.CompletionRequest
import scala.meta.internal.metals.debug.DebugProtocol.ErrorOutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.InitializeRequest
import scala.meta.internal.metals.debug.DebugProtocol.LaunchRequest
import scala.meta.internal.metals.debug.DebugProtocol.OutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.RestartRequest
import scala.meta.internal.metals.debug.DebugProtocol.SetBreakpointRequest
import scala.meta.internal.metals.debug.DebugProxy._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.debug.CompletionsResponse
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.Message

private[debug] final class DebugProxy(
    sessionName: String,
    client: RemoteEndpoint,
    server: ServerAdapter,
    debugAdapter: MetalsDebugAdapter,
    stackTraceAnalyzer: StacktraceAnalyzer,
    compilers: Compilers,
    stripColor: Boolean,
    statusBar: StatusBar,
    sourceMapper: SourceMapper,
)(implicit ec: ExecutionContext) {
  private val exitStatus = Promise[ExitStatus]()
  @volatile private var outputTerminated = false
  @volatile private var debugMode: DebugMode = DebugMode.Enabled
  private val cancelled = new AtomicBoolean()

  @volatile private var clientAdapter =
    ClientConfigurationAdapter.default(sourceMapper)
  @volatile private var lastFrames: Array[StackFrame] = Array.empty

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

  private val initialized = Promise[Unit]()

  private val handleClientMessage: MessageConsumer = {
    case null =>
      () // ignore
    case _ if cancelled.get() =>
      () // ignore
    case request @ InitializeRequest(args) =>
      statusBar.trackFuture(
        "Initializing debugger",
        initialized.future,
      )
      clientAdapter = ClientConfigurationAdapter.initialize(args, sourceMapper)
      server.send(request)
    case request @ LaunchRequest(debugMode) =>
      this.debugMode = debugMode
      server.send(request)
    case request @ RestartRequest(_) =>
      initialized.trySuccess(())
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
      val originalSource = DebugProtocol.copy(args.getSource)
      val metalsSourcePath = clientAdapter.toMetalsPath(originalSource.getPath)

      args.getBreakpoints.foreach { breakpoint =>
        val line = clientAdapter.normalizeLineForServer(
          metalsSourcePath,
          breakpoint.getLine,
        )
        breakpoint.setLine(line)
      }

      val requests =
        debugAdapter.adaptSetBreakpointsRequest(metalsSourcePath, args)
      server
        .sendPartitioned(requests.map(DebugProtocol.syntheticRequest))
        .map(_.map(DebugProtocol.parseResponse[SetBreakpointsResponse]))
        .map(_.flatMap(_.toList))
        .map(assembleResponse(_, originalSource))
        .map(DebugProtocol.syntheticResponse(request, _))
        .foreach(client.consume)

    case request @ CompletionRequest(args) =>
      val completions = for {
        frame <- lastFrames.find(_.getId() == args.getFrameId())
      } yield {
        val originalSource = frame.getSource()
        val sourceUri = clientAdapter.toMetalsPath(originalSource.getPath)
        compilers.debugCompletions(
          sourceUri,
          new Position(frame.getLine() - 1, 0),
          EmptyCancelToken,
          args,
        )
      }
      completions
        .getOrElse(Future.failed(new Exception("No source data available")))
        .map { items =>
          val responseArgs = new CompletionsResponse()
          responseArgs.setTargets(items.toArray)
          val response = new DebugResponseMessage
          response.setId(request.getId)
          response.setMethod(request.getMethod)
          response.setResult(responseArgs.toJson)
          client.consume(response)
        }
        .withTimeout(5, TimeUnit.SECONDS)

    case message => server.send(message)
  }

  private def assembleResponse(
      responses: Iterable[SetBreakpointsResponse],
      originalSource: Source,
  ): SetBreakpointsResponse = {
    val breakpoints = for {
      response <- responses
      breakpoint <- response.getBreakpoints
    } yield {
      val sourcePath = clientAdapter.toMetalsPath(originalSource.getPath)
      val line =
        clientAdapter.adaptLineForClient(sourcePath, breakpoint.getLine)
      breakpoint.setSource(originalSource)
      breakpoint.setLine(line)
      breakpoint
    }

    val response = new SetBreakpointsResponse
    response.setBreakpoints(breakpoints.toArray)
    response
  }

  private val handleServerMessage: Message => Unit = {
    case null => () // ignore
    case _ if cancelled.get() => () // ignore
    case OutputNotification(_) if outputTerminated => ()
    // ignore. When restarting, the output keeps getting printed for a short while after the
    // output window gets refreshed resulting in stale messages being printed on top, before
    // any actual logs from the restarted process
    case response @ DebugProtocol.StackTraceResponse(args) =>
      import scala.meta.internal.metals.JsonParser._
      for {
        stackFrame <- args.getStackFrames
        frameSource <- Option(stackFrame.getSource)
        frameSourcePath <- Option(frameSource.getPath)
        _ = stackFrame.setLine(
          clientAdapter.adaptLineForClient(
            clientAdapter.toMetalsPath(frameSourcePath),
            stackFrame.getLine,
          )
        )
        mappedSourcePath = clientAdapter
          .toMetalsPath(frameSourcePath, mappedFrom = true)
        metalsSource <- debugAdapter.adaptStackFrameSource(
          mappedSourcePath
        )
      } frameSource.setPath(clientAdapter.adaptPathForClient(metalsSource))
      response.setResult(args.toJson)
      lastFrames = args.getStackFrames()
      client.consume(response)
    case message @ ErrorOutputNotification(output) =>
      initialized.trySuccess(())
      val analyzedMessage = stackTraceAnalyzer
        .fileLocationFromLine(output.getOutput())
        .map(DebugProtocol.stacktraceOutputResponse(output, _))
        .getOrElse(message)
      client.consume(analyzedMessage)

    case message @ OutputNotification(output) if stripColor =>
      val raw = output.getOutput()
      // As long as the color codes are valid this should correctly strip
      // anything that is ESC (U+001B) plus [
      val msgWithoutColorCodes = raw.replaceAll("\u001B\\[[;\\d]*m", "");
      output.setOutput(msgWithoutColorCodes)
      message.setParams(output)
      client.consume(message)

    case message =>
      initialized.trySuccess(())
      client.consume(message)
  }

  def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      initialized.trySuccess(())
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
      awaitClient: () => Future[Socket],
      connectToServer: () => Future[Socket],
      debugAdapter: MetalsDebugAdapter,
      stackTraceAnalyzer: StacktraceAnalyzer,
      compilers: Compilers,
      workspace: AbsolutePath,
      stripColor: Boolean,
      status: StatusBar,
      sourceMapper: SourceMapper,
  )(implicit ec: ExecutionContext): Future[DebugProxy] = {
    for {
      server <- connectToServer()
        .map(new SocketEndpoint(_))
        .map(endpoint =>
          withLogger(workspace, endpoint, DebugProtocol.serverName)
        )
        .map(new MessageIdAdapter(_))
        .map(new ServerAdapter(_))
      client <- awaitClient()
        .map(new SocketEndpoint(_))
        .map(endpoint =>
          withLogger(workspace, endpoint, DebugProtocol.clientName)
        )
        .map(new MessageIdAdapter(_))
    } yield new DebugProxy(
      name,
      client,
      server,
      debugAdapter,
      stackTraceAnalyzer,
      compilers,
      stripColor,
      status,
      sourceMapper,
    )
  }

  private def withLogger(
      workspace: AbsolutePath,
      endpoint: RemoteEndpoint,
      name: String,
  ): RemoteEndpoint =
    Trace.setupTracePrinter(name, workspace) match {
      case Some(trace) => new EndpointLogger(endpoint, trace)
      case None => endpoint
    }
}
