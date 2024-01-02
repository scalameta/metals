package scala.meta.internal.metals.debug

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.StacktraceAnalyzer

import ch.epfl.scala.bsp4j.RunResult
import ch.epfl.scala.bsp4j.StatusCode
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.OutputEventArgumentsCategory
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugNotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.IdentifiableMessage
import org.eclipse.lsp4j.jsonrpc.messages.Message

/**
 * Runner used for basic DAP run requests without the overhead of
 * debugging. This onlt runs the program and responds very basic messages and
 * events to the DAP client.
 */
class DebugRunner(
    sessionName: String,
    client: RemoteEndpoint,
    stackTraceAnalyzer: StacktraceAnalyzer,
    runFuture: () => Future[RunResult],
    cancelling: Promise[Unit],
)(implicit ec: ExecutionContext) {

  val lastId = new AtomicInteger(DebugProtocol.FirstMessageId)
  private val clientReady: Promise[Unit] = Promise[Unit]()
  private val exitStatus: Future[DebugProxy.ExitStatus] =
    for {
      _ <- clientReady.future
      res <- runFuture().recover { case NonFatal(t) =>
        scribe.error(s"Error running $sessionName", t)
        new RunResult(StatusCode.ERROR)
      }
    } yield {
      val exited = new DebugNotificationMessage
      exited.setMethod("exited")
      val exitedArgs = new ExitedEventArguments()
      exitedArgs.setExitCode(res.getStatusCode().getValue())
      exited.setParams(exitedArgs)
      exited.setId(lastId.incrementAndGet())
      client.consume(exited)

      val terminated = new DebugNotificationMessage
      terminated.setMethod("terminated")
      terminated.setId(lastId.incrementAndGet())
      client.consume(terminated)
      DebugProxy.Terminated
    }

  private val cancelled = new AtomicBoolean()
  private def listenToClient(): Unit = {
    Future(client.listen(handleClientMessage)).andThen { case _ => cancel() }
  }

  lazy val listen: Future[DebugProxy.ExitStatus] = {
    scribe.info(s"Starting debug runner for [$sessionName]")
    listenToClient()
    exitStatus.map { st =>
      client.cancel()
      st
    }
  }

  private val handleClientMessage: MessageConsumer = { message =>
    setIdFromMessage(message)
    message match {
      case null =>
        () // ignore
      case _ if cancelled.get() =>
        () // ignore
      case request @ DebugProtocol.InitializeRequest(_) =>
        val response = DebugProtocol.EmptyResponse(request)
        response.setResult(new Capabilities)
        client.consume(response)

      case request @ DebugProtocol.LaunchRequest(_) =>
        val response = DebugProtocol.EmptyResponse(request)
        clientReady.trySuccess(())
        client.consume(response)

      case request @ DebugProtocol.ConfigurationDone(_) =>
        val response = DebugProtocol.EmptyResponse(request)
        client.consume(response)

      case request @ DebugProtocol.DisconnectRequest(_) =>
        val response = DebugProtocol.EmptyResponse(request)
        clientReady.trySuccess(())
        cancelling.trySuccess(())
        client.consume(response)

      case request @ DebugProtocol.TerminateRequest(_) =>
        val response = DebugProtocol.EmptyResponse(request)
        clientReady.trySuccess(())
        cancelling.trySuccess(())
        client.consume(response)

      case message =>
        scribe.debug("Message not handled:\n" + message)
    }
  }

  def setIdFromMessage(msg: Message): Unit = {
    msg match {
      case message: IdentifiableMessage =>
        Try(message.getId().toInt).foreach(lastId.set)
      case _ =>
    }
  }

  def stdout(message: String): Unit =
    output(message, OutputEventArgumentsCategory.STDOUT)(_ => None)

  def error(message: String): Unit = {
    output(message, OutputEventArgumentsCategory.STDERR) { notification =>
      stackTraceAnalyzer
        .fileLocationFromLine(message)
        .map(DebugProtocol.stacktraceOutputResponse(notification, _))

    }
  }

  private def output(message: String, category: String)(
      withDetails: OutputEventArguments => Option[DebugNotificationMessage]
  ) = {
    val output = new OutputEventArguments()
    output.setCategory(category)
    output.setOutput(message + "\n")

    def default = {
      val notification = new DebugNotificationMessage()
      notification.setMethod("output")
      notification.setParams(output)
      notification
    }

    val notification = withDetails(output).getOrElse(default)
    notification.setId(lastId.incrementAndGet())
    client.consume(notification)
  }

  def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      scribe.info(s"Canceling run for [$sessionName]")
      Cancelable.cancelAll(List(client))
    }
  }
}

object DebugRunner {

  def open(
      name: String,
      awaitClient: () => Future[Socket],
      stackTraceAnalyzer: StacktraceAnalyzer,
      runFuture: () => Future[RunResult],
      cancelling: Promise[Unit],
  )(implicit ec: ExecutionContext): Future[DebugRunner] = {
    for {
      client <- awaitClient()
        .map(new SocketEndpoint(_))
        .map(new MessageIdAdapter(_))
    } yield new DebugRunner(
      name,
      client,
      stackTraceAnalyzer,
      runFuture,
      cancelling,
    )
  }
}
