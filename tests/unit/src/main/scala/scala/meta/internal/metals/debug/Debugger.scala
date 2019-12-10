package scala.meta.internal.metals.debug
import java.lang
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.InitializeRequestArgumentsPathFormat
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * Provides simple facade over the Debug Adapter Protocol
 * by hiding the implementation details of how to build the
 * request arguments
 */
final class Debugger(server: RemoteServer)(implicit ec: ExecutionContext) {

  def initialize: Future[Capabilities] = {
    val arguments = new InitializeRequestArguments
    arguments.setAdapterID("test-adapter")
    arguments.setLinesStartAt1(true)
    arguments.setPathFormat(InitializeRequestArgumentsPathFormat.URI)
    server.initialize(arguments).asScala
  }

  def launch: Future[Unit] = {
    server.launch(Collections.emptyMap()).asScala.ignoreValue
  }

  def configurationDone: Future[Unit] = {
    server
      .configurationDone(new ConfigurationDoneArguments)
      .asScala
      .ignoreValue
  }

  def restart: Future[Unit] = {
    val args = new DisconnectArguments
    args.setRestart(true)
    server.disconnect(args).asScala.ignoreValue
  }

  def disconnect: Future[Unit] = {
    val args = new DisconnectArguments
    args.setRestart(false)
    args.setTerminateDebuggee(false)
    server.disconnect(args).asScala.ignoreValue
  }

  def setBreakpoints(
      source: Source,
      breakpoints: Array[SourceBreakpoint]
  ): Future[SetBreakpointsResponse] = {
    val args = new SetBreakpointsArguments
    args.setSource(source)
    args.setBreakpoints(breakpoints)
    server.setBreakpoints(args).asScala
  }

  def step(threadId: Long, nextStep: DebugStep): Future[Unit] = {
    nextStep match {
      case DebugStep.Continue =>
        val args = new ContinueArguments()
        args.setThreadId(threadId)
        server.continue_(args).asScala.ignoreValue
      case cause =>
        val error = s"Unsupported debug step $cause"
        Future.failed(new IllegalStateException(error))
    }
  }

  def stackFrame(threadId: lang.Long): Future[StackFrame] = {
    for {
      frame <- stackTrace(threadId).map(_.getStackFrames.head)
    } yield StackFrame(threadId, frame)

  }

  def stackTrace(thread: Long): Future[StackTraceResponse] = {
    val args = new StackTraceArguments
    args.setThreadId(thread)
    args.setLevels(1L)
    server.stackTrace(args).asScala
  }

  def shutdown: Future[Unit] = {
    server.listening.withTimeout(20, TimeUnit.SECONDS).andThen {
      case _ => server.cancel()
    }
  }
}
