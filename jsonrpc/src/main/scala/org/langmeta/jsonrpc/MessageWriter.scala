package org.langmeta.jsonrpc

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.circe.syntax._

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
class MessageWriter(out: OutputStream, logger: Logger) {
  private val ContentLen = "Content-Length"

  /** Lock protecting the output stream, so multiple writes don't mix message chunks. */
  private val lock = new Object

  /**
   * Write a message to the output stream. This method can be called from multiple threads,
   * but it may block waiting for other threads to finish writing.
   */
  def write[T: Encoder](msg: T, h: Map[String, String] = Map.empty): Unit =
    lock.synchronized {
      require(h.get(ContentLen).isEmpty)

      val json = msg.asJson.withObject(_.add("jsonrpc", "2.0".asJson).asJson)
      val str = json.noSpaces
      val contentBytes = str.getBytes(StandardCharsets.UTF_8)
      val headers = (h + (ContentLen -> contentBytes.length))
        .map { case (k, v) => s"$k: $v" }
        .mkString("", "\r\n", "\r\n\r\n")

      logger.trace(s" --> $str")

      val headerBytes = headers.getBytes(StandardCharsets.US_ASCII)

      out.write(headerBytes)
      out.write(contentBytes)
      out.flush()
    }
}
