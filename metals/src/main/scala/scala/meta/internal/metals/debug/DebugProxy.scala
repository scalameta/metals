package scala.meta.internal.metals.debug

import java.net.{Socket, URI}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.JsonParser.*
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.SourceMapper
import scala.meta.internal.metals.StacktraceAnalyzer
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.debug.DebugProtocol.CompletionRequest
import scala.meta.internal.metals.debug.DebugProtocol.DisconnectRequest
import scala.meta.internal.metals.debug.DebugProtocol.ErrorOutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.HotCodeReplace
import scala.meta.internal.metals.debug.DebugProtocol.InitializeRequest
import scala.meta.internal.metals.debug.DebugProtocol.LaunchRequest
import scala.meta.internal.metals.debug.DebugProtocol.OutputNotification
import scala.meta.internal.metals.debug.DebugProtocol.SetBreakpointRequest
import scala.meta.internal.metals.debug.DebugProtocol.TestResults
import scala.meta.internal.metals.debug.DebugProxy.*
import scala.meta.io.AbsolutePath
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.debugadapter.TestResultEvent
import ch.epfl.scala.debugadapter.testing.SingleTestResult.Failed
import ch.epfl.scala.debugadapter.testing.TestLocation
import ch.epfl.scala.debugadapter.testing.TestSuiteSummary
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.CompletionsResponse
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

private[debug] final class DebugProxy(
    sessionName: String,
    client: RemoteEndpoint,
    server: ServerAdapter,
    debugAdapter: MetalsDebugAdapter,
    stackTraceAnalyzer: StacktraceAnalyzer,
    compilers: Compilers,
    stripColor: Boolean,
    workDoneProgress: WorkDoneProgress,
    sourceMapper: SourceMapper,
    compilations: Compilations,
    targets: Seq[BuildTargetIdentifier],
)(implicit ec: ExecutionContext) {
  private val exitStatus = Promise[ExitStatus]()
  @volatile private var outputTerminated = false
  @volatile private var debugMode: DebugMode = DebugMode.Enabled
  private val cancelled = new AtomicBoolean()

  @volatile private var clientAdapter =
    ClientConfigurationAdapter.default(sourceMapper)
  private val frameIdToFrame: TrieMap[Int, StackFrame] = TrieMap.empty

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
      workDoneProgress.trackFuture(
        "Initializing debugger",
        initialized.future,
      )
      clientAdapter = ClientConfigurationAdapter.initialize(args, sourceMapper)
      server.send(request)
    case request @ LaunchRequest(debugMode) =>
      this.debugMode = debugMode
      server.send(request)
    case request @ DisconnectRequest(args) if args.getRestart() =>
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

      Try(clientAdapter.toMetalsPath(originalSource.getPath)) match {
        case Success(metalsSourcePath) =>
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
            .map(assembleResponse(_, originalSource, metalsSourcePath))
            .map(DebugProtocol.syntheticResponse(request, _))
            .foreach(client.consume)
        case Failure(_) =>
          scribe.warn(
            s"Cannot adapt SetBreakpointRequest because of invalid path: ${originalSource.getPath}"
          )
          val breakpoints = args.getBreakpoints.map { sourceBreakpoint =>
            val breakpoint = new Breakpoint
            breakpoint.setLine(sourceBreakpoint.getLine)
            breakpoint.setColumn(sourceBreakpoint.getColumn)
            breakpoint.setSource(originalSource)
            breakpoint.setVerified(false)
            breakpoint.setMessage(s"Invalid path: ${originalSource.getPath}")
            breakpoint
          }
          val response = new SetBreakpointsResponse
          response.setBreakpoints(breakpoints)
          client.consume(DebugProtocol.syntheticResponse(request, response))
      }

    case request @ CompletionRequest(args) =>
      if (args.getLine == null) {
        args.setLine(1)
      }
      val completions = for {
        frame <- frameIdToFrame.get(args.getFrameId())
      } yield {
        val originalSource = frame.getSource()
        val sourceUri = clientAdapter.toMetalsPath(originalSource.getPath)
        compilers.debugCompletions(
          sourceUri,
          new Position(frame.getLine() - 1, 0),
          EmptyCancelToken,
          args,
          isZeroBased = !clientAdapter.linesStartAt1,
        )
      }
      completions
        .getOrElse(Future.failed(new Exception("No source data available")))
        .recover { case NonFatal(t) =>
          scribe.error("Could not find any completions for the debugger", t)
          Nil
        }
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

    case HotCodeReplace(req) =>
      scribe.info("Hot code replace triggered")
      compilations
        .compileTargets(targets)
        .onComplete {
          case _: Success[?] => server.send(req)
          case Failure(e) =>
            val res = new DebugResponseMessage
            res.setId(req.getId)
            res.setMethod(req.getMethod)
            res.setError(
              new ResponseError(
                ResponseErrorCode.InternalError,
                s"Failed to compile ${e.getLocalizedMessage()}",
                null,
              )
            )
            client.consume(res)
        }

    case message => server.send(message)
  }

  private def assembleResponse(
      responses: Iterable[SetBreakpointsResponse],
      originalSource: Source,
      metalsSourcePath: AbsolutePath,
  ): SetBreakpointsResponse = {
    val breakpoints = for {
      response <- responses
      breakpoint <- response.getBreakpoints
    } yield {
      val line =
        clientAdapter.adaptLineForClient(metalsSourcePath, breakpoint.getLine)
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
      import DapJsonParser._
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
      val result = clientAdapter.adaptStackTraceResponse(args.toJsonObject)
      response.setResult(result)
      for (frame <- args.getStackFrames()) {
        frameIdToFrame.put(frame.getId, frame)
      }
      client.consume(response)
    case message @ ErrorOutputNotification(output) =>
      initialized.trySuccess(())
      client.consume(addStackTraceFileLocation(message, output))
    case message @ OutputNotification(output) if stripColor =>
      val raw = output.getOutput()
      val msgWithoutColorCodes = filterANSIColorCodes(raw)
      output.setOutput(msgWithoutColorCodes)
      message.setParams(output)
      client.consume(addStackTraceFileLocation(message, output))
    case message @ OutputNotification(output) =>
      client.consume(addStackTraceFileLocation(message, output))
    case message @ TestResults(testResult) =>
      message.setParams(modifyLocationInTests(testResult).toJson)
      client.consume(message)
    case message =>
      initialized.trySuccess(())
      client.consume(message)
  }

  private def addStackTraceFileLocation(
      message: NotificationMessage,
      output: OutputEventArguments,
  ) = {
    val lineWithoutColors = filterANSIColorCodes(output.getOutput())
    if (StackTraceMatcher.isStackTraceLine(lineWithoutColors))
      stackTraceAnalyzer
        .fileLocationFromLine(lineWithoutColors)
        .map(DebugProtocol.stacktraceOutputResponse(output, _))
        .getOrElse(message)
    else message
  }

  private def modifyLocationInTests(
      testResult: TestResultEvent
  ): TestResultEvent = {
    val updatedTests = for {
      test <- testResult.data.tests.asScala
    } yield {
      test match {
        case fail: Failed =>
          val possibleLocations = for {
            stack <- Option(fail.stackTrace).iterator
            stackLine <- stack.linesIterator
            location <- stackTraceAnalyzer.workspaceFileLocationFromLine(
              stackLine
            )
          } yield location

          possibleLocations.headOption match {
            case Some(value) =>
              Failed(
                fail.testName,
                fail.duration,
                fail.error,
                fail.stackTrace,
                new TestLocation(
                  value.getUri(),
                  value.getRange().getStart().getLine(),
                ),
              )

            case _ => fail
          }
        case other => other
      }
    }
    TestResultEvent(
      TestSuiteSummary(
        testResult.data.suiteName,
        testResult.data.duration,
        updatedTests.toList.asJava,
      )
    )
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
      workDoneProgress: WorkDoneProgress,
      sourceMapper: SourceMapper,
      compilations: Compilations,
      targets: Seq[BuildTargetIdentifier],
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
      workDoneProgress,
      sourceMapper,
      compilations,
      targets,
    )
  }

  private def withLogger(
      workspace: AbsolutePath,
      endpoint: RemoteEndpoint,
      name: String,
  )(implicit ec: ExecutionContext): RemoteEndpoint =
    Trace.setupTracePrinter(name, workspace) match {
      case Some(trace) => new EndpointLogger(endpoint, trace)
      case None => endpoint
    }
}

object StackTraceMatcher {
  private val stacktraceRegex =
    raw"at (?:[^\s(.]*\.)*[^\s(]*\([^\s).]*(?:.scala|.java|.sc)\:\d*\)".r
  def isStackTraceLine(line: String): Boolean = {
    line.trim() match {
      case stacktraceRegex() => true
      case _ => false
    }
  }
}
