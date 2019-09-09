package tests.debug

import org.eclipse.lsp4j.debug._
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient

trait ClientProxy extends IDebugProtocolClient {
  protected def client: IDebugProtocolClient

  override def initialized(): Unit =
    client.initialized()

  override def stopped(args: StoppedEventArguments): Unit =
    client.stopped(args)

  override def continued(args: ContinuedEventArguments): Unit =
    client.continued(args)

  override def exited(args: ExitedEventArguments): Unit =
    client.exited(args)

  override def terminated(args: TerminatedEventArguments): Unit =
    client.terminated(args)

  override def thread(args: ThreadEventArguments): Unit =
    client.thread(args)

  override def output(args: OutputEventArguments): Unit =
    client.output(args)

  override def breakpoint(args: BreakpointEventArguments): Unit =
    client.breakpoint(args)

  override def module(args: ModuleEventArguments): Unit =
    client.module(args)

  override def loadedSource(args: LoadedSourceEventArguments): Unit =
    client.loadedSource(args)

  override def process(args: ProcessEventArguments): Unit =
    client.process(args)

  override def capabilities(args: CapabilitiesEventArguments): Unit =
    client.capabilities(args)
}

object ClientProxy {
  def apply(delegatee: IDebugProtocolClient): ClientProxy = new ClientProxy {
    val client: IDebugProtocolClient = delegatee
  }
}
