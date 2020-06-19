package tests.debug

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import scala.meta.internal.metals.debug.DebugProtocol.FirstMessageId
import scala.meta.internal.metals.debug.MessageIdAdapter
import scala.meta.internal.metals.debug.TestingDebugServer

import munit.Location
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugRequestMessage
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.IdentifiableMessage
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import tests.BaseSuite

class MessageIdAdapterSuite extends BaseSuite {
  private val idCounter = new AtomicInteger(FirstMessageId)

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    idCounter.set(FirstMessageId)
  }

  test("synthetic-request") {
    val server = new TestingDebugServer
    val adapter = new MessageIdAdapter(server)

    adapter.consume(syntheticMessage())

    assertId(server.received.head, FirstMessageId)
  }

  test("maintain-id-sequence") {
    val server = new TestingDebugServer
    val adapter = new MessageIdAdapter(server)

    val messages = List(
      realMessage(),
      syntheticMessage(),
      realMessage(),
      syntheticMessage(),
      realMessage(),
      syntheticMessage()
    )

    messages.foreach(adapter.consume)

    // verify all messages were received
    for ((obtained, expected) <- server.received.zip(messages)) {
      assert(obtained == expected)
    }

    // verify message order
    val expectedIds =
      Iterator.iterate(FirstMessageId)(_ + 1).take(messages.size)

    for ((message, expectedId) <- server.received.zip(expectedIds.toList)) {
      assertId(message, expectedId)
    }
  }

  test("adapt-response-id") {
    val server = TestingDebugServer {
      case req: RequestMessage if req.getId.toInt == 2 =>
        val response = new DebugResponseMessage
        response.setId(2)
        response
    }

    val adapter = new MessageIdAdapter(server)

    val responses = mutable.Buffer.empty[Message]
    adapter.listen((message: Message) => responses.append(message))

    val messages = List(syntheticMessage(), realMessage())

    messages.foreach(adapter.consume)

    assertDiffEqual(responses.size, 1)
    assertId(responses.head, FirstMessageId)
  }

  def syntheticMessage(): RequestMessage = new DebugRequestMessage

  def realMessage(): RequestMessage = {
    val request = new DebugRequestMessage
    request.setId(idCounter.getAndIncrement())
    request
  }

  def assertId(obtained: Message, expected: Int)(implicit
      loc: Location
  ): Unit = {
    obtained match {
      case message: IdentifiableMessage =>
        assertDiffEqual(message.getId.toInt, expected)
      case _ =>
        fail(s"Not an identifiable message: $obtained")
    }
  }
}
