package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, ExecutionContext, Future}
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage

import scala.concurrent.duration.Duration

/**
 * Composite endpoint that manages multiple child endpoints for shared debug sessions.
 * Uses object identity for routing responses back to the originating endpoint.
 */
class CompositeDebugEndpoint(
    endpoints: Seq[RemoteEndpoint]
)(implicit ec: ExecutionContext)
    extends RemoteEndpoint {

  // Map request IDs to the endpoint that originated them
  private val requestRouting = TrieMap.empty[String, RemoteEndpoint]

  override def consume(message: Message): Unit = {
    message match {
      case resp: ResponseMessage =>
        // Route response back to originating endpoint
        requestRouting.remove(resp.getId) match {
          case Some(endpoint) =>
            endpoint.consume(resp)
          case None =>
            // Broadcast events/notifications to all endpoints
            endpoints.foreach(_.consume(message))
        }
      case _ =>
        // Broadcast all other messages (events, notifications)
        endpoints.foreach(_.consume(message))
    }
  }

  override def listen(consumer: MessageConsumer): Unit = {
    scribe.info(
      s"[CompositeDebugEndpoint] Starting to listen with ${endpoints.length} endpoints"
    )
    // Initialize all endpoints with routing - run each in parallel to avoid blocking
    val listenerFutures = endpoints.zipWithIndex.map { case (endpoint, index) =>
      Future {
        scribe.info(
          s"[CompositeDebugEndpoint] Starting listener for endpoint $index"
        )
        endpoint.listen(new MessageConsumer {
          def consume(message: Message): Unit = {
            message match {
              case req: RequestMessage =>
                // Track which endpoint sent this request
                scribe.info(
                  s"[CompositeDebugEndpoint] Request ${req.getId} from endpoint $index"
                )
                requestRouting.put(req.getId, endpoint)
                consumer.consume(message)
              case _ =>
                // Forward other messages directly to server
                consumer.consume(message)
            }
          }
        })
        scribe.info(
          s"[CompositeDebugEndpoint] Listener for endpoint $index started"
        )
      }.recover { case ex =>
        scribe.error(
          s"[CompositeDebugEndpoint] Failed to start listener for endpoint $index",
          ex,
        )
        throw ex
      }
    }

    try {
      Await.result(Future.sequence(listenerFutures).map(_ => ()), Duration.Inf)
      scribe.info(
        s"[CompositeDebugEndpoint] All ${endpoints.length} listeners started successfully"
      )
    } catch {
      case ex: Exception =>
        scribe.error(s"[CompositeDebugEndpoint] Failed to start listeners", ex)
        throw ex
    }
  }

  override def cancel(): Unit = {
    endpoints.foreach(_.cancel())
    requestRouting.clear()
  }
}
