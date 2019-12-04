package scala.meta.internal.metals.debug

import java.util.Collections
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
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

  def shutdown: Future[Unit] = {
    server.listening.withTimeout(20, TimeUnit.SECONDS).andThen {
      case _ => server.cancel()
    }
  }
}
