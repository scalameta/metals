package scala.meta.lsp

import java.io.InputStream
import monix.reactive.Observable

case class LSPMessage(header: Map[String, String], content: String) {
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

object LSPMessage {
  def fromInputStream(in: InputStream): Observable[LSPMessage] =
    Observable.fromInputStream(in).liftByOperator(new LSPMessageParser)
}
