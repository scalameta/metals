package tests.protocol

import java.nio.charset.StandardCharsets.US_ASCII

import org.langmeta.jsonrpc.BaseProtocolMessage
import org.langmeta.jsonrpc.BaseProtocolMessage._
import monix.execution.Scheduler
import monix.reactive.Observable
import scodec.bits._
import tests.MegaSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.meta.languageserver.protocol.BaseProtocolMessageParser.lspMessageExtractor
import monix.execution.schedulers.SchedulerService

object BaseProtocolMessageParserTest extends MegaSuite {
  implicit lazy val scheduler: SchedulerService = Scheduler.singleThread("test")

  val bodyString =
    """{"jsonrpc":"2.0","method":"textDocument/hover","params":{"textDocument":{"uri":"file:///Users/tutysara/src/myprojects/java/BroadleafCommerce/common/src/main/java/test.java"},"position":{"line":2,"character":7}},"id":17}"""
  val example = s"""$CONTENT_LENGTH_KEY: ${bodyString.length}
                   |
                   |$bodyString""".stripMargin.replaceAll("\n", "\r\n")
  val exampleWithMime = s"""$CONTENT_LENGTH_KEY:${bodyString.length}
                           |Content-Type:Json-Rpc
                           |
                           |$bodyString""".stripMargin.replaceAll("\n", "\r\n")
  val expect =
    BaseProtocolMessage(None, ByteVector(bodyString.getBytes(US_ASCII)))

  test("parses whole message from byteStream") {
    val stream =
      Observable(example.getBytes(US_ASCII)).transform(lspMessageExtractor)
    val result = drain(stream)
    assert(result.map(_.toString) == List(expect.toString))
  }

  test("parses whole message even when input is chunked") {
    val chunks = example.getBytes(US_ASCII).grouped(10).toList
    val stream = Observable(chunks: _*).transform(lspMessageExtractor)
    val result = drain(stream)
    assert(result.map(_.toString) == List(expect.toString))
  }

  def drain[A](input: Observable[A]): List[A] = {
    Await.result(input.toListL.runAsync, 3.seconds)
  }
}
