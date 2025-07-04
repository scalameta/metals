package scala.meta.internal.metals.debug

import java.net.Socket
import java.net.URI

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.Cancelable

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message

/**
 * Creates an MCP bridge to an existing debug session.
 * This allows MCP to interact with debug sessions that were started
 * from other clients (like VS Code).
 */
object McpDebugBridge {

  /**
   * Create an McpDebugSession that connects to an existing debug session.
   *
   * @param sessionId The session ID to use for the MCP session
   * @param debugUri The URI of the existing debug session (e.g., tcp://localhost:5005)
   * @param mcpCallback Function to send debug messages to MCP
   * @return A Future containing the McpDebugSession
   */
  def connectToExistingSession(
      sessionId: String,
      debugUri: URI,
      mcpCallback: Message => Unit,
  )(implicit ec: ExecutionContext): Future[McpDebugSession] = {
    val mcpAdapter = new McpEndpoint(mcpCallback)

    // Connect to the existing debug session
    val connectPromise = Promise[Socket]()
    Future {
      val socket = new Socket(debugUri.getHost, debugUri.getPort)
      connectPromise.success(socket)
    }.recover { case e =>
      connectPromise.failure(e)
    }

    connectPromise.future.map { socket =>
      // Create a bridge that forwards messages between the socket and MCP
      val socketEndpoint = new SocketEndpoint(socket)

      // Forward messages from socket to MCP
      Future {
        socketEndpoint.listen(new MessageConsumer {
          def consume(message: Message): Unit = {
            mcpAdapter.handleServerMessage(message)
          }
        })
      }

      // Set up MCP to send messages to socket
      mcpAdapter.listen(new MessageConsumer {
        def consume(message: Message): Unit = {
          socketEndpoint.consume(message)
        }
      })

      new McpDebugSession(sessionId, mcpAdapter)
    }
  }

  /**
   * Helper class to track active bridges and ensure cleanup
   */
  class BridgeTracker extends Cancelable {
    private val bridges = scala.collection.mutable.Map.empty[String, Cancelable]

    def addBridge(sessionId: String, bridge: Cancelable): Unit = {
      bridges.synchronized {
        bridges.put(sessionId, bridge)
      }
    }

    def removeBridge(sessionId: String): Unit = {
      bridges.synchronized {
        bridges.remove(sessionId).foreach(_.cancel())
      }
    }

    override def cancel(): Unit = {
      bridges.synchronized {
        bridges.values.foreach(_.cancel())
        bridges.clear()
      }
    }
  }
}
