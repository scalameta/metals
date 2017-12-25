package tests

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import scala.meta.lsp.LSPMessage
import monix.execution.CancelableFuture
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.schedulers.TestScheduler

object JsonRpcSuite extends MegaSuite {
  var out: PrintWriter = _
  var messages = List.empty[LSPMessage]
  implicit var s: TestScheduler = _
  var f: CancelableFuture[Unit] = CancelableFuture.unit
  override def utestBeforeEach(path: Seq[String]): Unit = {
    s = TestScheduler(AlwaysAsyncExecution)
    val in = new PipedInputStream
    out = new PrintWriter(new PipedOutputStream(in))
    messages = Nil
    f = LSPMessage.fromInputStream(in).foreach { msg =>
      messages = msg :: messages
    }
  }

  override def utestAfterEach(path: Seq[String]): Unit = {
    f.cancel()
  }

  def write(msg: String): Unit = {
    out.print(msg.replaceAll("\n", "\r\n"))
    out.flush()
    s.tickOne()
  }

  val header = """Content-Length: 43

"""
  val content = """{"jsonrpc":"2.0","id":1,"method":"example"}"""

  val message: String = header + content
  val lspMessage = LSPMessage(Map("Content-Length" -> "43"), content)

  test("header and content together") {
    write(message)
    assertEquals(messages, List(lspMessage))
  }

  test("combined") {
    write(message * 2)
    s.tickOne()
    assertEquals(messages, List(lspMessage, lspMessage))
  }

  test("header and content separately") {
    write(header)
    assert(messages.isEmpty)
    write(content)
    assertEquals(messages, List(lspMessage))
  }

}
