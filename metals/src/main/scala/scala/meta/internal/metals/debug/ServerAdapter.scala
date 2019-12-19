package scala.meta.internal.metals.debug
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.Cancelable

final class ServerAdapter(server: RemoteEndpoint)(implicit ec: ExecutionContext)
    extends Cancelable {
  private val partitions = TrieMap.empty[String, ResponseMessage => Unit]

  def onReceived(consumer: Message => Unit): Unit = {
    server.listen {
      case response: ResponseMessage if partitions.contains(response.getId) =>
        partitions
          .remove(response.getId)
          .foreach(callback => callback(response))
      // Don't consume. Partitioned messages are handled as a future
      case message =>
        consumer(message)
    }
  }

  def send(message: Message): Unit = {
    server.consume(message)
  }

  def sendPartitioned[Response](
      parts: Iterable[RequestMessage]
  ): Future[Iterable[ResponseMessage]] = {
    val responses = parts.map { request =>
      val promise = Promise[ResponseMessage]
      server.consume(request)
      partitions += (request.getId -> promise.success)

      promise.future
    }

    Future.sequence(responses)
  }

  override def cancel(): Unit = {
    server.cancel()
  }
}
