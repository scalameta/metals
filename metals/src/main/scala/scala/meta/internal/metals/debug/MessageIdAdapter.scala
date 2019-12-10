package scala.meta.internal.metals.debug

import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugRequestMessage
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.Message
import scala.collection.mutable
import scala.meta.internal.metals.debug.DebugProtocol.FirstMessageId

/**
 * Assigns an id to synthetic messages and fixes the id of
 * non-synthetic ones to keep the the id sequence monotonic.
 *
 * Additionally, adapts the request id for responses being produced
 * to keep the whole procedure transparent for the caller.
 */
final class MessageIdAdapter(next: RemoteEndpoint) extends RemoteEndpoint {
  private val messageCounter = new AtomicInteger(FirstMessageId)
  private val originalRequestIds = mutable.Map.empty[String, String]

  def nextId: Int = messageCounter.getAndIncrement()

  override def consume(message: Message): Unit = {
    message match {
      case response: DebugResponseMessage =>
        response.setResponseId(nextId)
      case request: DebugRequestMessage if request.getId == null =>
        request.setId(nextId)
      case request: DebugRequestMessage =>
        val originalId = request.getId
        val newId = nextId.toString
        if (originalId != newId) {
          originalRequestIds += (newId -> originalId)
          request.setId(newId)
        }
      case _ =>
      // ignore
    }
    next.consume(message)
  }

  override def listen(consumer: MessageConsumer): Unit = {
    next.listen { message =>
      message match {
        case response: DebugResponseMessage =>
          // restore id of the original request
          originalRequestIds
            .remove(response.getId)
            .foreach(response.setId)
        case _ =>
        // ignore
      }
      consumer.consume(message)
    }
  }

  override def cancel(): Unit = {
    next.cancel()
  }
}
