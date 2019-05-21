package scala.meta.internal.metals

import java.lang.StringBuilder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import fansi.ErrorMode

/**
 * Handles streaming console output from a system process with potential ANSI codes.
 *
 * @param onLine The callback handler when the process has printed a single line.
 *               Guaranteed to have no newline \n characters.
 */
class ProcessOutput(onLine: String => Unit) {
  private var buffer = new StringBuilder()

  /** The process has exited, clears out buffered output. */
  def onProcessExit(): Unit = {
    if (buffer.length() > 0) {
      onLine(buffer.toString())
      reset()
    }
  }

  def onByteOutput(bytes: ByteBuffer): this.type = {
    onPlainOutput(toPlainString(bytes))
  }

  def onStringOutput(text: String): this.type = {
    onPlainOutput(toPlainString(text))
  }

  private def onPlainOutput(text: String): this.type = {
    def loop(start: Int): Unit = {
      val newline = text.indexOf('\n', start)
      if (newline < 0) {
        buffer.append(text, start, text.length())
      } else {
        onLine(lineSubstring(text, start, newline))
        loop(newline + 1)
      }
    }
    loop(0)
    this
  }

  private def lineSubstring(text: String, from: Int, to: Int): String = {
    val end =
      // Convert \r\n line breaks into \n line breaks.
      if (to > 0 && text.charAt(to - 1) == '\r') to - 1
      else to
    if (buffer.length() == 0) text.substring(from, end)
    else {
      val line = buffer.append(text, from, to).toString()
      reset()
      line
    }
  }

  private def reset(): Unit = (
    buffer = new StringBuilder()
  )

  private def toPlainString(buffer: ByteBuffer): String = {
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    val ansiString = new String(bytes, StandardCharsets.UTF_8)
    toPlainString(ansiString)
  }

  private def toPlainString(ansiString: String): String = {
    fansi.Str(ansiString, ErrorMode.Sanitize).plainText
  }
}
