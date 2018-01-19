package org.langmeta.jsonrpc

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import com.typesafe.scalalogging.Logger
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

  /** Lock protecting the output stream, so multiple writes don't mix message chunks. */
  private val lock = new Object

  private val baos = new ByteArrayOutputStream()
  private val headerOut = MessageWriter.headerWriter(baos)

  /**
   * Write a message to the output stream. This method can be called from multiple threads,
   * but it may block waiting for other threads to finish writing.
   */
  def write(msg: Message): Future[Ack] = lock.synchronized {
    baos.reset()
    val json = msg.asJson
    val protocol = BaseProtocolMessage.fromJson(json)
    logger.trace(s" --> $json")
    val byteBuffer = MessageWriter.write(protocol, baos, headerOut)
    out.onNext(byteBuffer)
  }
}

object MessageWriter {

  def headerWriter(out: OutputStream): PrintWriter = {
    new PrintWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII))
  }

  def write(message: BaseProtocolMessage): ByteBuffer = {
    val out = new ByteArrayOutputStream()
    val header = headerWriter(out)
    write(message, out, header)
  }

  def write(
      message: BaseProtocolMessage,
      out: ByteArrayOutputStream,
      headerOut: PrintWriter
  ): ByteBuffer = {
    message.header.foreach {
      case (key, value) =>
        headerOut.write(key)
        headerOut.write(": ")
        headerOut.write(value)
        headerOut.write("\r\n")
    }
    headerOut.write("\r\n")
    headerOut.flush()
    out.write(message.content)
    val buffer = ByteBuffer.wrap(out.toByteArray, 0, out.size())
    buffer
  }
}
