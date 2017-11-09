package langserver.core

import java.io.PipedInputStream
import java.io.PipedOutputStream

import org.scalatest.BeforeAndAfter
import org.scalatest.Finders
import org.scalatest.FunSuite
import org.scalatest.OptionValues

import langserver.messages.ShowMessageParams
import play.api.libs.json.Json

class MessageWriterSuite extends FunSuite
    with BeforeAndAfter
    with OptionValues {

  var inStream: PipedInputStream = _
  var outStream: PipedOutputStream = _

  before {
    inStream = new PipedInputStream
    outStream = new PipedOutputStream(inStream)
  }

  after {
    outStream.close()
    inStream.close()
  }

  test("simple message can loop back") {
    implicit val f = Json.format[ShowMessageParams]
    val msgWriter = new MessageWriter(outStream)
    val msgReader = new MessageReader(inStream)

    val obj = ShowMessageParams(1, "test")
    msgWriter.write(obj)

    val payload = msgReader.nextPayload()
    val r = f.reads(Json.parse(payload.get))

    assert(r.isSuccess)
    assert(r.asOpt.value == obj)
  }
}
