package langserver.core

import java.io.PipedInputStream
import java.io.PipedOutputStream

import org.scalatest.FunSuite
import java.io.PrintWriter
import org.scalatest.OptionValues
import org.scalatest.BeforeAndAfter

class MessageReaderSuite extends FunSuite
    with BeforeAndAfter
    with OptionValues {

  var inStream: PipedInputStream = _
  var out: PrintWriter = _

  before {
    inStream = new PipedInputStream
    out = new PrintWriter(new PipedOutputStream(inStream))
  }

  after {
    out.close()
    inStream.close()
  }

  test("full headers are supported") {
    val msgReader = new MessageReader(inStream)

    write("""
Content-Length: 80
Content-Type: application/vscode-jsonrpc; charset=utf8

""")

    val headers = msgReader.getHeaders()

    assert(headers.value("Content-Length") == "80")
    assert(headers.value("Content-Type") == "application/vscode-jsonrpc; charset=utf8")
  }

  test("partial headers are correctly concatenated") {
    val msgReader = new MessageReader(inStream)

    write("""Content-Length: 80
Content""")
    write("""-Type: application/vscode-jsonrpc; charset=utf8

""")
    val headers = msgReader.getHeaders()

    assert(headers.value("Content-Length") == "80")
    assert(headers.value("Content-Type") == "application/vscode-jsonrpc; charset=utf8")
  }

  test("multi-chunk header") {
    val msgReader = new MessageReader(inStream)

    write("""Content-""")
    write("""Length: 80""")
    Thread.sleep(100)
    write("""
Content-Type: application/vscode-jsonrpc; charset=utf8

""")
    val headers = msgReader.getHeaders()

    assert(headers.value("Content-Length") == "80")
    assert(headers.value("Content-Type") == "application/vscode-jsonrpc; charset=utf8")
  }

  test("payload arrives") {
    val msgReader = new MessageReader(inStream)
    write("""Content-Length: 43

{"jsonrpc":"2.0","id":1,"method":"example"}""")

    val payload = msgReader.nextPayload()
    assert(payload == """{"jsonrpc":"2.0","id":1,"method":"example"}""")
  }

  test("chunked payload arrives") {
    val msgReader = new MessageReader(inStream)
    write("""Content-Length: 43

{"jsonrpc":"2.0",""")
    write(""""id":1,"method":"example"}""")

    val payload = msgReader.nextPayload()
    assert(payload == """{"jsonrpc":"2.0","id":1,"method":"example"}""")
  }

  private def write(msg: String): Unit = {
    out.print(msg.replaceAll("\n", "\r\n"))
    out.flush()
  }
}
