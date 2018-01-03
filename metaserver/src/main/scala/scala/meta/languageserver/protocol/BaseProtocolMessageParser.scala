package scala.meta.languageserver.protocol

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Ack
import monix.execution.Scheduler
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.observers.Subscriber

final class BaseProtocolMessageParser
    extends Operator[Array[Byte], BaseProtocolMessage]
    with LazyLogging {
  override def apply(
      out: Subscriber[BaseProtocolMessage]
  ): Subscriber[Array[Byte]] = {
    new Subscriber[Array[Byte]] {
      import Ack._
      // NOTE(olafur): We should first benchmark before going into any
      // optimization, but my intuition tells me ArrayBuffer[Byte] with many .remove
      // and ++= is wasteful and can probably be replaced with scodec-bits BitVector
      private[this] val data = ArrayBuffer.empty[Byte]
      private[this] var contentLength = -1
      private[this] var header = Map.empty[String, String]
      private[this] def atDelimiter(idx: Int): Boolean = {
        data.size >= idx + 4 &&
        data(idx) == '\r' &&
        data(idx + 1) == '\n' &&
        data(idx + 2) == '\r' &&
        data(idx + 3) == '\n'
      }
      private[this] val EmptyPair = "" -> ""
      private[this] def readHeaders(): Future[Ack] = {
        if (data.size < 4) Continue
        else {
          var i = 0
          while (i + 4 < data.size && !atDelimiter(i)) {
            i += 1
          }
          if (!atDelimiter(i)) Continue
          else {
            val bytes = new Array[Byte](i)
            data.copyToArray(bytes)
            data.remove(0, i + 4)
            val headers = new String(bytes, StandardCharsets.US_ASCII)
            // NOTE(olafur) all LSP messages from vscode seem to start with
            // Content-Length: N and include no other headers.
            // However, the spec says there can be other headers so we parse them
            // here anyways, even if we don't use any other values than Content-Length.
            val pairs: Map[String, String] = headers
              .split("\r\n")
              .iterator
              .filterNot(_.trim.isEmpty)
              .map { line =>
                line.split(":") match {
                  case Array(key, value) => key.trim -> value.trim
                  case _ =>
                    logger.error(s"Malformed input: $line")
                    EmptyPair
                }
              }
              .toMap
            pairs.get("Content-Length") match {
              case Some(n) =>
                try {
                  contentLength = n.toInt
                  header = pairs
                  readContent()
                } catch {
                  case _: NumberFormatException =>
                    logger.error(
                      s"Expected Content-Length to be a number, obtained $n"
                    )
                    Continue
                }
              case _ =>
                logger.error(s"Missing Content-Length key in headers $pairs")
                Continue
            }
          }
        }
      }
      private[this] def readContent(): Future[Ack] = {
        if (contentLength > data.size) Continue
        else {
          val contentBytes = new Array[Byte](contentLength)
          data.copyToArray(contentBytes)
          data.remove(0, contentLength)
          contentLength = -1
          val content = new String(contentBytes, StandardCharsets.UTF_8)
          out.onNext(BaseProtocolMessage(header, content)).flatMap {
            case Continue => readHeaders()
            case Stop => Stop
          }
        }
      }
      override implicit val scheduler: Scheduler = out.scheduler
      override def onError(ex: Throwable): Unit = out.onError(ex)
      override def onComplete(): Unit = {
        data.clear()
        out.onComplete()
      }
      override def onNext(elem: Array[Byte]): Future[Ack] = {
        data ++= elem
        if (contentLength < 0) readHeaders()
        else readContent()
      }
    }
  }
}
