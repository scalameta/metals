package langserver.core

import java.io.InputStream
import java.nio.charset.Charset

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import com.typesafe.scalalogging.LazyLogging

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
class MessageReader(in: InputStream) extends LazyLogging {
  val BufferSize = 8192

  private val buffer = new Array[Byte](BufferSize)
  @volatile
  private var data = ArrayBuffer.empty[Byte]
  @volatile
  private var streamClosed = false

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
      logger.info("End of stream, terminating thread")
      lock.synchronized {
        streamClosed = true
        lock.notify() // some threads might be still waiting for input
      }
    }
  }

  (new PumpInput).start()

  /**
   * Return headers, if any are available. It returns only full headers, after the
   * \r\n\r\n mark has been seen.
   *
   * @return A map of headers. If the map is empty it could be that the input stream
   *         was closed, or there were no headers before the delimiter. You can disambiguate
   *         by checking {{{this.streamClosed}}}
   */
  private[core] final def getHeaders(): Map[String, String] = lock.synchronized {
    val EmptyPair = "" -> ""
    val EmptyMap = Map.empty[String, String]
    def atDelimiter(idx: Int): Boolean = {
      (data.size >= idx + 4
        && data(idx) == '\r'
        && data(idx + 1) == '\n'
        && data(idx + 2) == '\r'
        && data(idx + 3) == '\n')
    }

    while (data.size < 4 && !streamClosed) lock.wait()

    if (streamClosed) return EmptyMap

    var i = 0
    while (i + 4 < data.size && !atDelimiter(i)) {
      i += 1
    }

    if (atDelimiter(i)) {
      val headers = new String(data.slice(0, i).toArray, MessageReader.AsciiCharset)
      logger.debug(s"Received headers:\n$headers")

      val pairs = headers.split("\r\n").filter(_.trim.length() > 0) map { line =>
        line.split(":") match {
          case Array(key, value) => key.trim -> value.trim
          case _ =>
            logger.error(s"Malformed input: $line")
            EmptyPair
        }
      }

      // drop headers
      data = data.drop(i + 4)

      // if there was a malformed header we keep trying to re-sync and read again
      if (pairs.exists(_ == EmptyPair)) {
        logger.error("There was an empty pair in $pairs, trying to read another header.")
        getHeaders
      } else pairs.toMap
    } else if (streamClosed) {
      EmptyMap
    } else {
      lock.wait()
      getHeaders()
    }
  }

  /**
   * Return `len` bytes of content as a string encoded in UTF8.
   *
   * @note If the stream was closed this method returns the empty string.
   */
  private [core] def getContent(len: Int): String = lock.synchronized {
    while (data.size < len && !streamClosed) lock.wait()

    if (streamClosed) ""
    else {
      assert(data.size >= len)
      val content = data.take(len).toArray
      data = data.drop(len)
      new String(content, MessageReader.Utf8Charset)
    }
  }

  /**
   * Return the next JSON RPC content payload. Blocks until enough data has been received.
   */
  def nextPayload(): Option[String] = if (streamClosed) None else {
    // blocks until headers are available
    val headers = getHeaders()

    if (headers.isEmpty && streamClosed)
      None
    else {
      val length = headers.get("Content-Length") match {
        case Some(len) => try len.toInt catch { case e: NumberFormatException => -1 }
        case _ => -1
      }

      if (length > 0) {
        val content = getContent(length)
        if (content.isEmpty() && streamClosed) None else Some(content)
      } else {
        logger.error("Input must have Content-Length header with a numeric value.")
        nextPayload()
      }
    }
  }
}

object MessageReader {
  val AsciiCharset = Charset.forName("ASCII")
  val Utf8Charset = Charset.forName("UTF-8")
}

