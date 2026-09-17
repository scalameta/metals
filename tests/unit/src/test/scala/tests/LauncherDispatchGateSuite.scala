package tests

import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.LauncherDispatchGate

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage

class LauncherDispatchGateSuite extends BaseSuite {

  private def notification(method: String): NotificationMessage = {
    val message = new NotificationMessage
    message.setMethod(method)
    message
  }

  private def dispatchAll(
      gate: LauncherDispatchGate,
      messages: List[Message],
  ): List[Message] = {
    val received = ListBuffer.empty[Message]
    val consumer: MessageConsumer = (message: Message) => {
      received += message
      ()
    }
    messages.foreach(gate.dispatch(consumer, _))
    received.toList
  }

  test("active gate dispatches every message") {
    val gate = new LauncherDispatchGate
    val messages = List[Message](
      notification("build/taskStart"),
      notification("build/taskFinish"),
      notification("build/publishDiagnostics"),
      new RequestMessage,
      new ResponseMessage,
    )
    assertEquals(dispatchAll(gate, messages), messages)
  }

  test("in-flight `build/taskStart` does not block other messages") {
    val gate = new LauncherDispatchGate
    val taskStartRunning = new java.util.concurrent.CountDownLatch(1)
    val releaseTaskStart = new java.util.concurrent.CountDownLatch(1)
    val blockingConsumer: MessageConsumer = (_: Message) => {
      taskStartRunning.countDown()
      releaseTaskStart.await()
    }
    val inFlight = new Thread(() =>
      gate.dispatch(blockingConsumer, notification("build/taskStart"))
    )
    inFlight.start()
    taskStartRunning.await()
    // While a `build/taskStart` is still being dispatched, messages outside
    // the gate's policy must be delivered without waiting; state-mutating
    // server notifications deliberately synchronize with the gate.
    val outgoing = notification("build/exit")
    assertEquals(
      dispatchAll(gate, List[Message](outgoing, new ResponseMessage)).size,
      2,
    )
    releaseTaskStart.countDown()
    inFlight.join()
  }

  test("closed gate drops every state-mutating server notification") {
    val gate = new LauncherDispatchGate
    gate.close()
    // outgoing notifications must keep flowing, `build/exit` above all, or
    // the server would never terminate
    val outgoing = notification("build/exit")
    val request = new RequestMessage
    val response = new ResponseMessage
    val messages = List[Message](
      notification("build/taskStart"),
      notification("build/taskFinish"),
      notification("build/taskProgress"),
      notification("build/publishDiagnostics"),
      notification("build/logMessage"),
      notification("build/showMessage"),
      notification("buildTarget/didChange"),
      outgoing,
      request,
      response,
    )
    assertEquals(
      dispatchAll(gate, messages),
      List[Message](outgoing, request, response),
    )
  }
}
