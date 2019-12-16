package scala.meta.internal.metals.debug
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import scala.collection.mutable

final class TestingDebugServer(
    respondTo: PartialFunction[Message, ResponseMessage] = PartialFunction.empty
) extends RemoteEndpoint {
  private val consumed = mutable.Buffer.empty[Message]
  private var listener: MessageConsumer = (_: Message) => ()

  def received: List[Message] = consumed.toList

  override def cancel(): Unit = ()
  override def consume(message: Message): Unit = {
    consumed += message
    respondTo.lift(message).foreach(listener.consume)
  }
  override def listen(messageConsumer: MessageConsumer): Unit = {
    listener = messageConsumer
  }
}

object TestingDebugServer {

  def apply(
      respondTo: PartialFunction[Message, ResponseMessage]
  ): TestingDebugServer =
    new TestingDebugServer(respondTo)
}
