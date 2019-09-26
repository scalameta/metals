package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.TestDebugger.Prefix

final class TestDebugger(connect: RemoteServer.Listener => RemoteServer)(
    implicit ec: ExecutionContext
) extends RemoteServer.Listener {

  @volatile private var server: RemoteServer = connect(this)
  private var terminationPromise = Promise[Unit]()
  private val outputBuffer = new StringBuilder
  private val prefixes = mutable.Set.empty[Prefix]

  def initialize: Future[Capabilities] = {
    val arguments = new InitializeRequestArguments
    arguments.setAdapterID("test-adapter")
    server.initialize(arguments).asScala
  }

  def launch: Future[Unit] = {
    for {
      _ <- server.launch(Collections.emptyMap()).asScala
      _ <- server.configurationDone(new ConfigurationDoneArguments).asScala
    } yield ()
  }

  def restart: Future[Unit] = {
    val args = new DisconnectArguments
    args.setRestart(true)
    for {
      _ <- server.disconnect(args).asScala
      _ <- awaitCompletion
    } yield {
      terminationPromise = Promise()
      server = connect(this)
      outputBuffer.clear()
    }
  }

  def disconnect: Future[Unit] = {
    val args = new DisconnectArguments
    args.setRestart(false)
    args.setTerminateDebuggee(false)
    server.disconnect(args).asScala.ignoreValue
  }

  /**
   * Not waiting for exited because it might not be sent
   */
  def awaitCompletion: Future[Unit] = {
    for {
      _ <- terminationPromise.future
      _ <- server.listening.onTimeout(20, TimeUnit.SECONDS)(this.close())
    } yield ()
  }

  def awaitOutput(expected: String): Future[Unit] = {
    val prefix = Prefix(expected, Promise())
    prefixes += prefix

    if (output.startsWith(expected)) {
      prefix.promise.success(())
      prefixes -= prefix
    }

    prefix.promise.future
  }

  def output: String = outputBuffer.toString()

  def close(): Unit = {
    server.cancel()
    prefixes.foreach { prefix =>
      val message = s"Output did not start with ${prefix.pattern}"
      prefix.promise.failure(new IllegalStateException(message))
    }
  }

  override def onOutput(event: OutputEventArguments): Unit = {
    outputBuffer.append(event.getOutput)
    val out = output
    val matched = prefixes.filter(prefix => out.startsWith(prefix.pattern))
    matched.foreach { prefix =>
      prefixes.remove(prefix)
      prefix.promise.success(())
    }
  }

  override def onTerminated(): Unit = {
    terminationPromise.trySuccess(())
  }
}

object TestDebugger {
  def apply(uri: URI)(implicit ec: ExecutionContext): TestDebugger = {
    def connect(listener: RemoteServer.Listener): RemoteServer = {
      val socket = new Socket()
      socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 2000)
      RemoteServer(socket, listener)
    }

    new TestDebugger(connect)
  }

  final case class Prefix(pattern: String, promise: Promise[Unit])
}
