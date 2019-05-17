package scala.meta.internal.eval

import org.eclipse.lsp4j.debug.BreakpointEventArguments
import org.eclipse.lsp4j.debug.CapabilitiesEventArguments
import org.eclipse.lsp4j.debug.ContinuedEventArguments
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.LoadedSourceEventArguments
import org.eclipse.lsp4j.debug.ModuleEventArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.ProcessEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.ThreadEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient

final class DelegatingDebugClient extends IDebugProtocolClient {
  private var underlying: IDebugProtocolClient = new IDebugProtocolClient {}

  def setUnderlying(client: IDebugProtocolClient): Unit = {
    underlying = client
  }

  override def initialized(): Unit = underlying.initialized()

  override def stopped(args: StoppedEventArguments): Unit =
    underlying.stopped(args)

  override def continued(args: ContinuedEventArguments): Unit =
    underlying.continued(args)

  override def exited(args: ExitedEventArguments): Unit =
    underlying.exited(args)

  override def terminated(args: TerminatedEventArguments): Unit =
    underlying.terminated(args)

  override def thread(args: ThreadEventArguments): Unit =
    underlying.thread(args)

  override def output(args: OutputEventArguments): Unit =
    underlying.output(args)

  override def breakpoint(args: BreakpointEventArguments): Unit =
    underlying.breakpoint(args)

  override def module(args: ModuleEventArguments): Unit =
    underlying.module(args)

  override def loadedSource(args: LoadedSourceEventArguments): Unit =
    underlying.loadedSource(args)

  override def process(args: ProcessEventArguments): Unit =
    underlying.process(args)

  override def capabilities(args: CapabilitiesEventArguments): Unit =
    underlying.capabilities(args)
}
