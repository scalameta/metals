package org.langmeta.jsonrpc

import java.io.OutputStream
import java.nio.charset.StandardCharsets

import com.typesafe.scalalogging.LazyLogging
import io.circe.Encoder
import io.circe.syntax._
import scodec.Attempt.{Failure, Successful}
import scodec.Attempt

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
class MessageWriter(out: OutputStream) extends LazyLogging {

  /** Lock protecting the output stream, so multiple writes don't mix message chunks. */
  private val lock = new Object

  /**
   * Write a message to the output stream. This method can be called from multiple threads,
   * but it may block waiting for other threads to finish writing.
   */
  def write[T: Encoder](msg: T): Unit =
    lock.synchronized {
      val str = msg.asJson.noSpaces
      val contentBytes = str.getBytes(StandardCharsets.UTF_8)
      val encoded: Attempt[Array[Byte]] =
        BaseProtocolMessage.codec
          .encode(BaseProtocolMessage.fromJsonRpcBytes(contentBytes))
          .map(_.toByteArray)

      encoded match {
        case Successful(bytes) =>
          logger.debug(s" --> $str")
          out.write(bytes)
          out.flush()
        case Failure(err) =>
          logger.error(s"Failed to encode message $msg}", err)
      }
    }
}
