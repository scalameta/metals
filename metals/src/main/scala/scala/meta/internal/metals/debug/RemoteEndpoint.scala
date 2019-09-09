package scala.meta.internal.metals.debug

import java.net.Socket
import java.util.Collections

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.MessageProducer
import org.eclipse.lsp4j.jsonrpc.debug.json.DebugMessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer
import org.eclipse.lsp4j.jsonrpc.messages.Message

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.debug.RemoteEndpoint._

private[debug] final class RemoteEndpoint(socket: Socket)
    extends MessageConsumer
    with MessageProducer
    with Cancelable {
  private val source = messageSource(socket)
  private val target = messageTarget(socket)

  override def consume(message: Message): Unit = {
    target.consume(message)
  }

  override def listen(consumer: MessageConsumer): Unit = {
    source.listen(consumer)
  }

  override def cancel(): Unit = {
    socket.close()
  }
}

private[debug] object RemoteEndpoint {
  private val handler = new DebugMessageJsonHandler(Collections.emptyMap())

  private def messageSource(socket: Socket): MessageProducer = {
    new StreamMessageProducer(socket.getInputStream, handler)
  }

  private def messageTarget(socket: Socket): MessageConsumer = {
    new StreamMessageConsumer(socket.getOutputStream, handler)
  }
}
