package langserver.core

import langserver.messages.UnparsedLspMessage
import monix.execution.Ack.Continue
import monix.execution.{Ack, Scheduler}
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

/*
  Splits incoming bytes into unparsed LspMessages
 */
object LspMessageReaderOperator extends Operator[Array[Byte], List[UnparsedLspMessage]]{
  //move this into LspMessage?
  val CONTENT_LENGTH_KEY = "Content-Length"
  def parseHeaders(input: String): Map[String, String] = {
    val result = input.lines
      .filter(_.nonEmpty)
      .map(_.split(":"))
      .map { case Array(k, v) => k -> v.trim }
      .toMap
    assert(result.contains(CONTENT_LENGTH_KEY), "Could not parse Content-Length from header")
    result
  }

  sealed trait HeaderDelimiter {
    val CR: Byte = '\r'.toByte
    val NL: Byte = '\n'.toByte
    def getNext(byte: Byte): HeaderDelimiter = this match {
      case _ if !((byte == CR) || (byte == NL)) => NotStarted //exit early if not interesting byte
      case NotStarted if byte == CR => MatchedOneByte
      case MatchedOneByte if byte == NL => MatchedTwoBytes
      case MatchedTwoBytes if byte == CR => MatchedThreeBytes
      case MatchedThreeBytes if byte == NL => CompleteHeaderDelimiter
      case CompleteHeaderDelimiter if byte == CR => MatchedOneByte
      case _ => NotStarted //If we don't find the next byte we are looking for reset the search state
    }
  }
  case object NotStarted extends HeaderDelimiter
  case object MatchedOneByte extends HeaderDelimiter
  case object MatchedTwoBytes extends HeaderDelimiter
  case object MatchedThreeBytes extends HeaderDelimiter
  case object CompleteHeaderDelimiter extends HeaderDelimiter

  def apply(out: Subscriber[List[UnparsedLspMessage]]): Subscriber[Array[Byte]] =
    new Subscriber[Array[Byte]] {
      //There are two reasons to stop reading bytes, either \r\n\r\n has been reached
      //or n bytes have been read
      private val unusedBuffer = Array.newBuilder[Byte]
      private var bytesNeeded = 0
      private var headerDelimiterState: HeaderDelimiter = NotStarted
      private var headers: Map[String, String] = Map()

      implicit def scheduler: Scheduler = out.scheduler
      override def onError(ex: Throwable): Unit = out.onError(ex)
      override def onComplete(): Unit = out.onComplete()

      override def onNext(elem: Array[Byte]): Future[Ack] = {
        val messages = List.newBuilder[UnparsedLspMessage]
        //I opted for simplicity of implementation over performance here.
        //Performance could be improved by operating over segments of bytes instead of a byte at a time
        def loop(idx: Int): Unit = {
          val byte = elem(idx)
          unusedBuffer += byte //buffer bytes until we have a complete header or body
          headerDelimiterState match {
            case CompleteHeaderDelimiter if bytesNeeded == 1 =>
              //We just got the last byte needed, fetch contents now
              headerDelimiterState == NotStarted
              bytesNeeded = 0
              val msg = UnparsedLspMessage(headers, unusedBuffer.result())
              unusedBuffer.clear()
              headers = Map[String, String]()
              messages += msg
            case CompleteHeaderDelimiter => //waiting for content bytes
              bytesNeeded -= 1
            case _ => //waiting for header delimiter
              headerDelimiterState = headerDelimiterState.getNext(byte)
              if (headerDelimiterState == CompleteHeaderDelimiter) {
                val header = new String(unusedBuffer.result(), "UTF-8")
                unusedBuffer.clear
                headers = parseHeaders(header)
                bytesNeeded = headers(CONTENT_LENGTH_KEY).toInt
              }
          }
        }

        elem.indices.foreach(i => loop(i))
        val msgs = messages.result()
        if (msgs.isEmpty) Continue else out.onNext(msgs)
      }
    }
}


