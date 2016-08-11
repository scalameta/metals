package langserver.core

import java.io.OutputStream
import play.api.libs.json._
import java.nio.charset.Charset
import com.typesafe.scalalogging.LazyLogging

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
  private val ContentLen = "Content-Length"
  
  def write[T](msg: T, h: Map[String, String] = Map.empty)(implicit o: Format[T]): Unit = {
    require(h.get(ContentLen).isEmpty)
    
    val str = Json.stringify(o.writes(msg))
    val contentBytes = str.getBytes(MessageReader.Utf8Charset)
    val headers = (h + (ContentLen -> contentBytes.length))
      .map { case (k, v) => s"$k: $v" }
      .mkString("", "\r\n", "\r\n\r\n")
  
    logger.debug(s"Headers: \n$headers")
    
    val headerBytes = headers.getBytes(MessageReader.AsciiCharset)
    
    out.write(headerBytes)
    out.write(contentBytes)
    out.flush()
  }
}