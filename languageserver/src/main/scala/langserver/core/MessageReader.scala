package langserver.core

import java.io.InputStream
import java.nio.charset.Charset

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
 * A Language Server message Reader. It expects the following format:
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
class MessageReader(in: InputStream) {
  val BufferSize = 8192
  
  private val buffer = new Array[Byte](BufferSize)
  private var data = ArrayBuffer.empty[Byte]
  
  private val lock = new Object

  private class PumpInput extends Thread("Input Reader") {
    override def run() {
      var nRead = 0
      do {
        nRead = in.read(buffer)
        if (nRead > 0) lock.synchronized { 
          data ++= buffer.slice(0, nRead)
          lock.notify()
        }
      } while (nRead > 0) 
      Console.err.println("End of stream, terminating thread")
    }
  }

  (new PumpInput).start()

  /**
   * Return headers, if any are available. It returns only full headers, after the
   * \r\n\r\n mark has been seen.
   */
  private[core] final def getHeaders(): Option[Map[String, String]] = lock.synchronized {
    val EmptyPair = "" -> ""
    def atDelimiter(idx: Int): Boolean = {
      (data.size >= idx + 4
        && data(idx) == '\r'
        && data(idx + 1) == '\n'
        && data(idx + 2) == '\r'
        && data(idx + 3) == '\n')
    }
    
    if (data.size < 4) lock.wait()

    var i = 0
    while (i + 4 < data.size && !atDelimiter(i)) {
      i += 1
    }

    if (atDelimiter(i)) {
      val headers = new String(data.slice(0, i).toArray, Charset.forName("ASCII"))
      Console.err.println(s"Received headers:\n$headers")
      
      val pairs = headers.split("\r\n").filter(_.trim.length() > 0) map { line =>
        line.split(":") match {
          case Array(key, value) => key.trim -> value.trim
          case _ => 
            Console.err.println(s"Malformed input: $line")
            EmptyPair
        }
      }

      // drop headers
      data = data.drop(i + 4)
      if (pairs.exists(_ == EmptyPair)) None else Some(pairs.toMap)
    } else {
      lock.wait()
      getHeaders()
    }
  }
  
  private [core] def getContent(len: Int): String = lock.synchronized {
    while (data.size < len) lock.wait()
    
    assert(data.size >= len)
    val content = data.take(len).toArray
    data = data.drop(len)
    new String(content, Charset.forName("UTF-8"))
  }
  
  /**
   * Return the next JSON RPC content payload. Blocks until enough data has been received.
   */
  def nextPayload(): String = {
    // blocks until headers are available
    val headers = getHeaders().get
    
    val length = headers.get("Content-Length") match {
      case Some(len) => try len.toInt catch { case e: NumberFormatException => -1 } 
      case _ => -1
    }
    
    if (length > 0)
      getContent(length)
    else {
      Console.err.println("Input must have Content-Length header with a numeric value.")
      nextPayload()
    }
  }
}



