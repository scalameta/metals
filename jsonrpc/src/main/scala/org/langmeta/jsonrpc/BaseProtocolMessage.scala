package org.langmeta.jsonrpc

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.scalalogging.Logger
import monix.execution.ExecutionModel
import monix.execution.Scheduler
import monix.reactive.Observable

case class BaseProtocolMessage(header: Map[String, String], content: String) {
  override def toString: String = {
    val sb = new java.lang.StringBuilder()
    header.foreach {
      case (key, value) =>
        sb.append(key).append(": ").append(value).append("\r\n")
    }
    sb.append("\r\n")
      .append(content)
    sb.toString
  }
}

object BaseProtocolMessage extends LazyLogging {

  def fromInputStream(in: InputStream): Observable[BaseProtocolMessage] =
    fromInputStream(in, logger)

  def fromInputStream(
      in: InputStream,
      logger: Logger
  ): Observable[BaseProtocolMessage] =
    fromBytes(Observable.fromInputStream(in), logger)

  def fromBytes(
      in: Observable[Array[Byte]],
      logger: Logger
  ): Observable[BaseProtocolMessage] =
    fromByteBuffers(in.map(ByteBuffer.wrap), logger)

  def fromByteBuffers(
      in: Observable[ByteBuffer],
      logger: Logger
  ): Observable[BaseProtocolMessage] =
    in.executeOn(
        Scheduler(
          Executors.newFixedThreadPool(1),
          ExecutionModel.AlwaysAsyncExecution
        )
      )
      .liftByOperator(new BaseProtocolMessageParser(logger))
}
