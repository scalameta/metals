package scala.meta.internal.metals.debug

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.CompletionsArguments
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
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
    arguments.setPathFormat(InitializeRequestArgumentsPathFormat.PATH)
    server.initialize(arguments).asScala
  }

  def launch(debug: java.lang.Boolean): Future[Unit] = {
    val arguments = Map[String, AnyRef](
      "noDebug" -> java.lang.Boolean.valueOf(!debug)
    )
    server.launch(arguments.asJava).asScala.ignoreValue
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
    server.disconnect(args).asScala.ignoreValue
  }

  def setBreakpoints(
      source: Source,
      breakpoints: Array[SourceBreakpoint],
  ): Future[SetBreakpointsResponse] = {
    val args = new SetBreakpointsArguments
    args.setSource(source)
    args.setBreakpoints(breakpoints)
    server.setBreakpoints(args).asScala
  }

  def step(threadId: Int, nextStep: DebugStep): Future[Unit] = {
    nextStep match {
      case DebugStep.Continue =>
        val args = new ContinueArguments()
        args.setThreadId(threadId)
        server.continue_(args).asScala.ignoreValue
      case DebugStep.StepIn =>
        val args = new StepInArguments()
        args.setThreadId(threadId)
        server.stepIn(args).asScala.ignoreValue
      case DebugStep.StepOut =>
        val args = new StepOutArguments()
        args.setThreadId(threadId)
        server.stepOut(args).asScala.ignoreValue
      case DebugStep.StepOver =>
        val args = new NextArguments()
        args.setThreadId(threadId)
        server.next(args).asScala.ignoreValue
      case DebugStep.Evaluate(expression, frameId, callback, nextStep) =>
        val args = new EvaluateArguments()
        args.setFrameId(frameId)
        args.setExpression(expression)
        server
          .evaluate(args)
          .asScala
          .map(callback)
          .flatMap(_ => step(threadId, nextStep))
      case DebugStep.Complete(expression, frameId, callback, line, character) =>
        val args = new CompletionsArguments()
        args.setFrameId(frameId)
        args.setText(expression)
        args.setLine(line)
        args.setColumn(character)
        server.completions(args).asScala.flatMap { completions =>
          callback(completions)
          step(threadId, DebugStep.Continue)
        }
    }
  }

  def stackFrame(threadId: Int): Future[StackFrame] = {
    for {
      frame <- stackTrace(threadId).map(_.getStackFrames.head)
      scopes <- scopes(frame.getId).map(_.getScopes)
      variables <- {
        val scopeVariables = scopes.map { scope =>
          variables(scope.getVariablesReference).map { response =>
            val variables = response.getVariables.map(Variable.apply)
            scope.getName -> variables.toList
          }
        }

        Future
          .sequence(scopeVariables.toList)
          .map(scopes => Variables(scopes.toMap))
      }
    } yield StackFrame(threadId, frame, variables)

  }

  def stackTrace(thread: Int): Future[StackTraceResponse] = {
    val args = new StackTraceArguments
    args.setThreadId(thread)
    args.setLevels(1)
    server.stackTrace(args).asScala
  }

  def scopes(frame: Int): Future[ScopesResponse] = {
    val args = new ScopesArguments
    args.setFrameId(frame)
    server.scopes(args).asScala
  }

  def variables(id: Int): Future[VariablesResponse] = {
    val args = new VariablesArguments
    args.setVariablesReference(id)
    server
      .variables(args)
      .asScala
  }

  def shutdown(timeout: Int = 20): Future[Unit] = {
    server.listening.withTimeout(timeout, TimeUnit.SECONDS).andThen { case _ =>
      server.cancel()
    }
  }
}
