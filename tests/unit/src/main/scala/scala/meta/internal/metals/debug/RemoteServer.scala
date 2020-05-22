package scala.meta.internal.metals.debug

import java.net.Socket
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.reflect.ClassTag
import scala.reflect.classTag

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.DebugProtocol.FirstMessageId

import com.google.gson.JsonElement
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug._
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugRequestMessage
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage

private[debug] final class RemoteServer(
    socket: Socket,
    listener: RemoteServer.Listener
)(implicit ec: ExecutionContext)
    extends IDebugProtocolServer
    with Cancelable {

  type Response = DebugResponseMessage
  type Request = DebugRequestMessage
  type Notification = NotificationMessage

  private val remote = new SocketEndpoint(socket)
  private val ongoing = new TrieMap[String, Response => Unit]()
  private val id = new AtomicInteger(FirstMessageId)
  lazy val listening: Future[Unit] = Future(listen())

  override def initialize(
      args: InitializeRequestArguments
  ): CompletableFuture[Capabilities] = {
    sendRequest("initialize", args)
  }

  override def launch(
      args: util.Map[String, AnyRef]
  ): CompletableFuture[Void] = {
    sendRequest("launch", args)
  }

  override def configurationDone(
      args: ConfigurationDoneArguments
  ): CompletableFuture[Void] = {
    sendRequest("configurationDone", args)
  }

  override def setBreakpoints(
      args: SetBreakpointsArguments
  ): CompletableFuture[SetBreakpointsResponse] = {
    sendRequest("setBreakpoints", args)
  }

  override def stackTrace(
      args: StackTraceArguments
  ): CompletableFuture[StackTraceResponse] = {
    sendRequest("stackTrace", args)
  }

  override def scopes(
      args: ScopesArguments
  ): CompletableFuture[ScopesResponse] = {
    sendRequest("scopes", args)
  }

  override def variables(
      args: VariablesArguments
  ): CompletableFuture[VariablesResponse] = {
    sendRequest("variables", args)
  }

  override def continue_(
      args: ContinueArguments
  ): CompletableFuture[ContinueResponse] = {
    sendRequest("continue", args)
  }

  override def next(
      args: NextArguments
  ): CompletableFuture[Void] = {
    sendRequest("next", args)
  }

  override def stepIn(
      args: StepInArguments
  ): CompletableFuture[Void] = {
    sendRequest("stepIn", args)
  }

  override def stepOut(
      args: StepOutArguments
  ): CompletableFuture[Void] = {
    sendRequest("stepOut", args)
  }

  override def disconnect(
      args: DisconnectArguments
  ): CompletableFuture[Void] = {
    sendRequest("disconnect", args)
  }

  private def listen(): Unit = {
    remote.listen {
      case response: Response =>
        ongoing.remove(response.getId) match {
          case Some(callback) =>
            callback(response)
          case None =>
            scribe.error(s"Response to invalid message: [$response]")
        }
      case notification: Notification =>
        notification.getMethod match {
          case "output" =>
            notify(notification, listener.onOutput)
          case "stopped" =>
            notify(notification, listener.onStopped)
          case "terminated" =>
            listener.onTerminated()
          case _ =>
            scribe.debug(s"Unsupported notification: ${notification.getMethod}")
        }
      case msg =>
        scribe.error(s"Message [$msg] is not supported")
    }
  }

  private def notify[A: ClassTag](msg: Notification, f: A => Unit): Unit = {
    msg.getParams match {
      case json: JsonElement =>
        json.as[A].map(f).recover {
          case e => scribe.error(s"Could not handle notification [msg]", e)
        }
      case _ =>
        scribe.error(s"Not a json: ${msg.getParams}")
    }
  }

  private def sendRequest[A, B: ClassTag](
      endpoint: String,
      arg: A
  ): CompletableFuture[B] = {
    val request = new Request()
    request.setId(id.getAndIncrement())
    request.setMethod(endpoint)
    request.setParams(arg)

    val promise = Promise[Response]()
    ongoing.put(request.getId, response => promise.success(response))
    remote.consume(request)

    val expectedType = classTag[B].runtimeClass.asInstanceOf[Class[B]]
    val response = promise.future.flatMap { response =>
      response.getResult match {
        case null if expectedType == classOf[Void] =>
          Future[Void](null).asInstanceOf[Future[B]]
        case json: JsonElement =>
          Future.fromTry(json.as[B])
        case _ if response.getError != null =>
          Future.failed(new IllegalStateException(response.getError.getMessage))
        case result =>
          Future.failed(new IllegalStateException(s"not a json: $result"))
      }
    }

    response.onTimeout(90, TimeUnit.SECONDS)(logTimeout(endpoint)).asJava
  }

  private def logTimeout(endpoint: String): Unit = {
    scribe.error(s"Timeout when waiting for a response to $endpoint request")
  }

  override def cancel(): Unit = {
    remote.cancel()
  }
}

object RemoteServer {
  trait Listener {
    def onOutput(event: OutputEventArguments): Unit
    def onStopped(event: StoppedEventArguments): Unit
    def onTerminated(): Unit
  }

  def apply(socket: Socket, listener: Listener)(implicit
      ec: ExecutionContext
  ): RemoteServer = {
    val server = new RemoteServer(socket, listener)
    server.listening.onComplete(_ => server.cancel())
    server
  }
}
