package tests.debug

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.debug._
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import tests.debug.TestDebugger.Prefix
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.language.implicitConversions
import scala.meta.internal.metals.MetalsEnrichments._

final class TestDebugger(connect: IDebugProtocolClient => ServerConnection)(
    implicit ec: ExecutionContext
) extends IDebugProtocolClient
    with AutoCloseable {

  /**
   * Converts java future to scala one, adding a timeout to prevent the test hanging
   */
  private[this] implicit def toScalaFuture[A](
      f: CompletableFuture[A]
  ): Future[A] = {
    f.asScala.withTimeout(10, TimeUnit.SECONDS)
  }

  @volatile protected var server: ServerConnection = connect(this)

  private var terminationPromise = Promise[Unit]()
  private val outputs = mutable.Map.empty[String, StringBuilder]
  private val prefixes = mutable.Set.empty[Prefix]

  def initialize: Future[Capabilities] = {
    val arguments = new InitializeRequestArguments
    arguments.setAdapterID("test-adapter")
    server.initialize(arguments)
  }

  def launch: Future[Unit] = {
    for {
      _ <- server.launch(Collections.emptyMap())
      _ <- server.configurationDone(new ConfigurationDoneArguments)
    } yield ()
  }

  def restart: Future[Unit] = {
    val args = new DisconnectArguments
    args.setRestart(true)
    for {
      _ <- server.disconnect(args)
      _ <- awaitCompletion
    } yield {
      terminationPromise = Promise()
      server = connect(this)
      outputs.clear()
    }
  }

  def disconnect: Future[Unit] = {
    server.disconnect(new DisconnectArguments).map(_ => ())
  }

  /**
   * Not waiting for exited because it might not be sent
   */
  def awaitCompletion: Future[Unit] = {
    for {
      _ <- terminated
      _ <- server.listening.onTimeout(20, TimeUnit.SECONDS)(this.close())
    } yield ()
  }

  def terminated: Future[Unit] = terminationPromise.future

  def awaitOutput(expected: String): Future[Unit] = {
    val prefix = Prefix(expected, Promise())
    prefixes += prefix

    if (output.startsWith(expected)) {
      prefix.promise.success(())
      prefixes -= prefix
    }

    prefix.promise.future
  }

  ////////////////////////////////////////////////////////
  override def terminated(args: TerminatedEventArguments): Unit = {
    terminationPromise.success(())
  }

  def output: String = {
    output(OutputEventArgumentsCategory.STDOUT).toString()
  }

  override def output(args: OutputEventArguments): Unit = {
    output(args.getCategory).append(args.getOutput)
    val matched = prefixes.filter(prefix => output.startsWith(prefix.pattern))
    matched.foreach { prefix =>
      prefixes.remove(prefix)
      prefix.promise.success(())
    }
  }

  private def output(arg: String): mutable.StringBuilder = {
    outputs.getOrElseUpdate(arg, new mutable.StringBuilder())
  }

  override def close(): Unit = {
    server.cancel()
    prefixes.foreach { prefix =>
      val message = s"Output did not start with ${prefix.pattern}"
      prefix.promise.failure(new IllegalStateException(message))
    }
  }
}

object TestDebugger {
  def apply(
      uri: URI
  )(implicit ec: ExecutionContextExecutorService): TestDebugger = {
    def connect(client: IDebugProtocolClient): ServerConnection = {
      val socket = new Socket()
      socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 2000)
      ServerConnection.open(socket, ClientProxy(client))
    }

    new TestDebugger(connect)
  }

  final case class Prefix(pattern: String, promise: Promise[Unit])
}
