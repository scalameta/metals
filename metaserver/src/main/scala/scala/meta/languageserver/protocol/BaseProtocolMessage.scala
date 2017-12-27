package scala.meta.languageserver.protocol

import java.io.InputStream
import java.util.concurrent.Executors
import monix.execution.Scheduler
import monix.reactive.Observable

case class BaseProtocolMessage(header: Map[String, String], content: String) {
  override def toString: String = {
    val sb = new java.lang.StringBuilder()
    header.foreach {
      case (key, value) =>
        sb.append(key).append(": ").append(value).append("\n")
    }
    sb.append("\n")
      .append(content)
    sb.toString
  }
}

object BaseProtocolMessage {
  def fromInputStream(in: InputStream): Observable[BaseProtocolMessage] =
    Observable
      .fromInputStream(in)
      .executeOn(Scheduler(Executors.newFixedThreadPool(1)))
      .liftByOperator(new BaseProtocolMessageParser)
}
