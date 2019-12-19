package scala.meta.internal.metals.debug

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.util.Collections
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.json.DebugMessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import scala.meta.internal.metals.debug.SocketEndpoint._

private[debug] final class SocketEndpoint(socket: Socket)
    extends RemoteEndpoint {
  private val source = messageSource(socket)
  private val target = messageTarget(socket)

  override def consume(message: Message): Unit = {
    target.consume(message)
  }

  override def listen(consumer: MessageConsumer): Unit = {
    source.listen(consumer)
  }

  override def cancel(): Unit = {
    source.close()
    socket.close()
  }
}

private[debug] object SocketEndpoint {
  private val handler = new DebugMessageJsonHandler(Collections.emptyMap())

  private def messageSource(socket: Socket): StreamMessageProducer = {
    val stream = new BufferedInputStream(socket.getInputStream)
    new StreamMessageProducer(stream, handler)
  }

  private def messageTarget(socket: Socket): MessageConsumer = {
    val stream = new BufferedOutputStream(socket.getOutputStream)
    new StreamMessageConsumer(stream, handler)
  }
}
