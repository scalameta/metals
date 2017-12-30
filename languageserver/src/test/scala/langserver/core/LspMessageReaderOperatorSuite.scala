package langserver.core

import scala.concurrent.Await
import scala.concurrent.duration._
import langserver.messages.UnparsedLspMessage
import monix.execution._
import monix.reactive.Observable
import org.scalatest.FunSuite
import LspMessageReaderOperator.CONTENT_LENGTH_KEY

class LspMessageReaderOperatorSuite extends FunSuite {
  implicit lazy val scheduler = Scheduler.singleThread("test")

  val bodyString = """{"jsonrpc":"2.0","method":"textDocument/hover","params":{"textDocument":{"uri":"file:///Users/tutysara/src/myprojects/java/BroadleafCommerce/common/src/main/java/test.java"},"position":{"line":2,"character":7}},"id":17}"""
  val example = s"""$CONTENT_LENGTH_KEY: ${bodyString.length}
                  |
                  |$bodyString""".stripMargin.replaceAll("\n", "\r\n")
  val expect = UnparsedLspMessage(Map(CONTENT_LENGTH_KEY -> bodyString.length.toString), bodyString.getBytes("UTF-8"))

  test("parses whole message from byteStream") {
    val stream = Observable(example.getBytes("UTF-8"))
      .liftByOperator(LspMessageReaderOperator)
    val result = drain(stream).flatten
    assert(result.map(_.toString) == List(expect.toString))
  }

  test("parses whole message even when input is chunked") {
    val chunks = example.getBytes("UTF-8").grouped(10).toList
    val stream = Observable(chunks: _*)
      .liftByOperator(LspMessageReaderOperator)
    val result = drain(stream).flatten
    assert(result.map(_.toString) == List(expect.toString))
  }


  def drain[A](input: Observable[A]): List[A] = {
    Await.result(input.toListL.runAsync, 3.seconds)
  }

}
