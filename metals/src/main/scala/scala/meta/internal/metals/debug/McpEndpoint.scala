package scala.meta.internal.metals.debug

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.debug.DebugProtocol.FirstMessageId

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage

/**
 * Adapter that bridges between MCP's callback-based approach and DAP's RemoteEndpoint protocol.
 *
 * This adapter:
 * - Implements RemoteEndpoint to integrate with DebugProxy
 * - Maintains correlation between requests and responses
 * - Provides a Future-based API for MCP tools
 *
 * @param mcpCallback Function to send messages back to MCP client
 * @param ec Execution context for async operations
 */
class McpEndpoint(
    mcpCallback: Message => Unit
)(implicit ec: ExecutionContext)
    extends RemoteEndpoint {

  private val pendingRequests = TrieMap.empty[String, Promise[ResponseMessage]]
  private val requestIdCounter = new AtomicInteger(FirstMessageId)
  private val initialized = Promise[Unit]()
  private val shutdownPromise = Promise[Unit]()
  private var messageConsumer: MessageConsumer = _
  @volatile private var cancelled = false

  /**
   * Send a request and receive a Future response.
   * This is the main API for MCP tools to interact with the debug session.
   */
  def sendRequest(request: RequestMessage): Future[ResponseMessage] = {
    // Assign an ID if the request doesn't have one
    if (request.getId == null) {
      request.setId(requestIdCounter.getAndIncrement().toString)
    }

    if (cancelled) {
      Future.failed(new IllegalStateException("Debug adapter is cancelled"))
    } else {
      // Wait for initialization before sending the request
      initialized.future.flatMap { _ =>
        val promise = Promise[ResponseMessage]()
        pendingRequests.put(request.getId, promise)

        // Send to debug server via the consumer
        if (messageConsumer != null) {
          messageConsumer.consume(request)
        } else {
          promise.failure(new IllegalStateException("No message consumer set"))
        }

        // Add timeout to prevent hanging forever
        val timeoutDuration = Duration(30, TimeUnit.SECONDS)
        Future.firstCompletedOf(
          List(
            promise.future,
            Future {
              Thread.sleep(timeoutDuration.toMillis)
              val removed = pendingRequests.remove(request.getId)
              scribe.error(
                s"[McpDebugAdapter] Request ${request.getId} timed out after ${timeoutDuration}. Was pending: ${removed.isDefined}"
              )
              throw new RuntimeException(
                s"Request ${request.getId} timed out after ${timeoutDuration}"
              )
            },
          )
        )
      }
    }
  }

  /**
   * Handle messages coming from the debug server.
   * MCP should call this when it receives a message from the debug server.
   */
  def handleServerMessage(message: Message): Unit = {
    scribe.info(
      s"[McpDebugAdapter] handleServerMessage received: ${message.getClass.getSimpleName}"
    )
    message match {
      case response: ResponseMessage =>
        scribe.info(
          s"[McpDebugAdapter] Received response for request ID: ${response.getId}"
        )
        // Check if this is a response to one of our pending requests
        pendingRequests.remove(response.getId) match {
          case Some(promise) =>
            scribe.info(
              s"[McpDebugAdapter] Found pending request for ID ${response.getId}, completing promise"
            )
            promise.trySuccess(response)
          case None =>
            scribe.warn(
              s"[McpDebugAdapter] No pending request found for response ID: ${response.getId}"
            )
        }
        // Also forward to MCP for logging/monitoring
        mcpCallback(message)
      case _ =>
        scribe.info(
          s"[McpDebugAdapter] Forwarding non-response message to MCP: ${message.getClass.getSimpleName}"
        )
        // Forward all other messages (notifications, etc.) to MCP
        mcpCallback(message)
    }
  }

  // RemoteEndpoint implementation

  /**
   * Called by DebugProxy to send messages to the client (MCP in this case)
   */
  override def consume(message: Message): Unit = {
    if (!cancelled) {
      handleServerMessage(message)
    }
  }

  /**
   * Called by DebugProxy to set up the message consumer for sending to server
   */
  override def listen(consumer: MessageConsumer): Unit = {
    this.messageConsumer = consumer
    initialized.trySuccess(())

    // Block until the adapter is cancelled, similar to how SocketEndpoint blocks
    // This prevents the debug session from terminating prematurely
    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    try {
      Await.result(shutdownPromise.future, Duration.Inf)
    } catch {
      case _: InterruptedException =>
        // Thread was interrupted, treat as cancellation
        cancel()
    }
  }

  override def cancel(): Unit = {
    cancelled = true
    // Fail initialization if not yet completed
    initialized.tryFailure(new RuntimeException("Debug adapter cancelled"))
    // Signal shutdown to unblock the listen method
    shutdownPromise.trySuccess(())
    // Fail all pending requests
    pendingRequests.values.foreach { promise =>
      promise.tryFailure(new RuntimeException("Debug adapter cancelled"))
    }
    pendingRequests.clear()
  }
}
