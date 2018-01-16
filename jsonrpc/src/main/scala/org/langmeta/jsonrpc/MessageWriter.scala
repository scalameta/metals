package org.langmeta.jsonrpc

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.circe.syntax._
import monix.execution.Ack
import monix.reactive.Observer

/**
 * A class to write Json RPC messages on an output stream, following the Language Server Protocol.
 * It produces the following format:
 *
 * <Header> '\r\n' <Content>
 *
 * Header := FieldName ':' FieldValue '\r\n'
 *
 * Currently there are two defined header fields:
 * - 'Content-Length' in bytes (required)
 * - 'Content-Type' (string), defaults to 'application/vscode-jsonrpc; charset=utf8'
 *
 * @note The header part is defined to be ASCII encoded, while the content part is UTF8.
 */
class MessageWriter(out: Observer[ByteBuffer], logger: Logger) {
  private val ContentLen = "Content-Length"

  // TODO(olafur) is this necessary now that we refactored to Observer?
  /** Lock protecting the output stream, so multiple writes don't mix message chunks. */
  private val lock = new Object

  /**
   * Write a message to the output stream. This method can be called from multiple threads,
   * but it may block waiting for other threads to finish writing.
   */
  def write[T: Encoder](
      msg: T,
      h: Map[String, String] = Map.empty
  ): Future[Ack] =
    lock.synchronized {
      require(h.get(ContentLen).isEmpty)

      val json = msg.asJson.mapObject(_.add("jsonrpc", "2.0".asJson))
      val str = json.noSpaces
      val contentBytes = str.getBytes(StandardCharsets.UTF_8)
      val headers = (h + (ContentLen -> contentBytes.length))
        .map { case (k, v) => s"$k: $v" }
        .mkString("", "\r\n", "\r\n\r\n")

      logger.trace(s" --> $str")

      val headerBytes = headers.getBytes(StandardCharsets.US_ASCII)
      // TODO(olafur) slim down on allocations!
      val bb = ByteBuffer.allocate(headerBytes.length + contentBytes.length)
      bb.put(headerBytes)
      bb.put(contentBytes)
      bb.flip()
      out.onNext(bb)
    }
}
