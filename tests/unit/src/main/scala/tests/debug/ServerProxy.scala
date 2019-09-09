package tests.debug

import java.util.Collections
import java.util.concurrent.CompletableFuture

import org.eclipse.lsp4j.debug._
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer

trait ServerProxy extends IDebugProtocolServer {
  protected def server: IDebugProtocolServer

  override def runInTerminal(
      args: RunInTerminalRequestArguments
  ): CompletableFuture[RunInTerminalResponse] =
    server.runInTerminal(args)

  override def initialize(
      args: InitializeRequestArguments
  ): CompletableFuture[Capabilities] =
    server.initialize(args)

  def configurationDone: CompletableFuture[Void] = {
    configurationDone(new ConfigurationDoneArguments)
  }

  override def configurationDone(
      args: ConfigurationDoneArguments
  ): CompletableFuture[Void] =
    server.configurationDone(args)

  def launch: CompletableFuture[Void] = {
    launch(Collections.emptyMap())
  }

  override def launch(
      args: java.util.Map[String, AnyRef]
  ): CompletableFuture[Void] =
    server.launch(args)

  override def attach(
      args: java.util.Map[String, AnyRef]
  ): CompletableFuture[Void] = {
    server.attach(args)
  }

  override def restart(args: RestartArguments): CompletableFuture[Void] =
    server.restart(args)

  override def disconnect(args: DisconnectArguments): CompletableFuture[Void] =
    server.disconnect(args)

  override def terminate(args: TerminateArguments): CompletableFuture[Void] =
    server.terminate(args)

  override def setBreakpoints(
      args: SetBreakpointsArguments
  ): CompletableFuture[SetBreakpointsResponse] =
    server.setBreakpoints(args)

  override def setFunctionBreakpoints(
      args: SetFunctionBreakpointsArguments
  ): CompletableFuture[SetFunctionBreakpointsResponse] =
    server.setFunctionBreakpoints(args)

  override def setExceptionBreakpoints(
      args: SetExceptionBreakpointsArguments
  ): CompletableFuture[Void] =
    server.setExceptionBreakpoints(args)

  override def continue_(
      args: ContinueArguments
  ): CompletableFuture[ContinueResponse] =
    server.continue_(args)

  override def next(args: NextArguments): CompletableFuture[Void] =
    server.next(args)

  override def stepIn(args: StepInArguments): CompletableFuture[Void] =
    server.stepIn(args)

  override def stepOut(args: StepOutArguments): CompletableFuture[Void] =
    server.stepOut(args)

  override def stepBack(args: StepBackArguments): CompletableFuture[Void] =
    server.stepBack(args)

  override def reverseContinue(
      args: ReverseContinueArguments
  ): CompletableFuture[Void] =
    server.reverseContinue(args)

  override def restartFrame(
      args: RestartFrameArguments
  ): CompletableFuture[Void] =
    server.restartFrame(args)

  override def goto_(args: GotoArguments): CompletableFuture[Void] =
    server.goto_(args)

  override def pause(args: PauseArguments): CompletableFuture[Void] =
    server.pause(args)

  override def stackTrace(
      args: StackTraceArguments
  ): CompletableFuture[StackTraceResponse] =
    server.stackTrace(args)

  override def scopes(
      args: ScopesArguments
  ): CompletableFuture[ScopesResponse] =
    server.scopes(args)

  override def variables(
      args: VariablesArguments
  ): CompletableFuture[VariablesResponse] =
    server.variables(args)

  override def setVariable(
      args: SetVariableArguments
  ): CompletableFuture[SetVariableResponse] =
    server.setVariable(args)

  override def source(
      args: SourceArguments
  ): CompletableFuture[SourceResponse] =
    server.source(args)

  override def threads(): CompletableFuture[ThreadsResponse] =
    server.threads()

  override def terminateThreads(
      args: TerminateThreadsArguments
  ): CompletableFuture[Void] =
    server.terminateThreads(args)

  override def modules(
      args: ModulesArguments
  ): CompletableFuture[ModulesResponse] =
    server.modules(args)

  override def loadedSources(
      args: LoadedSourcesArguments
  ): CompletableFuture[LoadedSourcesResponse] =
    server.loadedSources(args)

  override def evaluate(
      args: EvaluateArguments
  ): CompletableFuture[EvaluateResponse] =
    server.evaluate(args)

  override def setExpression(
      args: SetExpressionArguments
  ): CompletableFuture[SetExpressionResponse] =
    server.setExpression(args)

  override def stepInTargets(
      args: StepInTargetsArguments
  ): CompletableFuture[StepInTargetsResponse] =
    server.stepInTargets(args)

  override def gotoTargets(
      args: GotoTargetsArguments
  ): CompletableFuture[GotoTargetsResponse] =
    server.gotoTargets(args)

  override def completions(
      args: CompletionsArguments
  ): CompletableFuture[CompletionsResponse] =
    server.completions(args)

  override def exceptionInfo(
      args: ExceptionInfoArguments
  ): CompletableFuture[ExceptionInfoResponse] =
    server.exceptionInfo(args)
}
