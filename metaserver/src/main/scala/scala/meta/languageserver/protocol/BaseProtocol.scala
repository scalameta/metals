package scala.meta.languageserver.protocol

import java.io.InputStream
import monix.reactive.Observable

case class BaseProtocol(header: Map[String, String], content: String) {
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

object BaseProtocol {
  def fromInputStream(in: InputStream): Observable[BaseProtocol] =
    Observable.fromInputStream(in).liftByOperator(new ProtocolParser)
}
