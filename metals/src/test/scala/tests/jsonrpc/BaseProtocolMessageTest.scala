package tests.jsonrpc

import java.nio.ByteBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax._
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import org.langmeta.jsonrpc._
import tests.MegaSuite

object BaseProtocolMessageTest extends MegaSuite with LazyLogging {
  val request = Request("method", Some("params".asJson), RequestId(1))
  val message = BaseProtocolMessage(request)
  val byteArray = MessageWriter.write(message).array()
  val byteArray2 = byteArray ++ byteArray
  def bytes: ByteBuffer = ByteBuffer.wrap(byteArray)

  test("toString") {
    assertNoDiff(
      message.toString,
      """|Content-Length: 62
         |
         |{"method":"method","params":"params","id":"1","jsonrpc":"2.0"}""".stripMargin
    )
  }

  val s = TestScheduler()

  def await[T](f: Task[T]): T = {
    val a = f.runAsync(s)
    while (s.tickOne()) ()
    Await.result(a, Duration("5s"))
  }

  def parse(buffers: List[ByteBuffer]): List[BaseProtocolMessage] = {
    val buf = List.newBuilder[BaseProtocolMessage]
    val t = BaseProtocolMessage
      .fromByteBuffers(Observable(buffers: _*), logger)
      // NOTE(olafur) toListL will not work as expected here, it will send onComplete
      // for the first onNext, even when a single ByteBuffer can contain multiple
      // messages
      .foreachL(buf += _)
    await(t)
    buf.result()
  }

  def pairs(n: Int): List[(ByteBuffer, BaseProtocolMessage)] =
    1.to(n).toList.map(_ => bytes -> message)

  0.to(4).foreach { i =>
    test(s"parse-$i") {
      val (buffers, messages) = pairs(i).unzip
      assertEquals(parse(buffers), messages)
    }
  }

  def checkTwoMessages(name: String, buffers: List[ByteBuffer]): Unit = {
    test(name) {
      val obtained = parse(buffers)
      val expected = List(message, message)
      assertEquals(obtained, expected)
    }
  }
  def array: ByteBuffer = ByteBuffer.wrap(byteArray)
  def take(n: Int): ByteBuffer = ByteBuffer.wrap(byteArray.take(n))
  def drop(n: Int): ByteBuffer = ByteBuffer.wrap(byteArray.drop(n))

  checkTwoMessages(
    "combined",
    ByteBuffer.wrap(byteArray2) ::
      Nil
  )

  checkTwoMessages(
    "chunked",
    take(10) ::
      drop(10) ::
      array ::
      Nil
  )

  checkTwoMessages(
    "chunked2",
    take(10) ::
      ByteBuffer.wrap(drop(10).array() ++ take(10).array()) ::
      drop(10) ::
      Nil
  )

  test("chunked-property") {
    0.to(byteArray2.length).foreach { i =>
      val buffers =
        ByteBuffer.wrap(byteArray2.take(i)) ::
          ByteBuffer.wrap(byteArray2.drop(i)) ::
          Nil
      val obtained = parse(buffers)
      val expected = List(message, message)
      assertEquals(obtained, expected, hint = s"chunked, i=$i")
    }
  }
}
