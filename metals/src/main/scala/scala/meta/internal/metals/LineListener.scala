package scala.meta.internal.metals

import java.lang.StringBuilder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import fansi.ErrorMode

/**
 * Converts a stream of strings (with potential ANSI codes and newlines) into a callback for indvidual lines
 * where each individual line has no newline \n characters.
 *
 * @param onLine The callback handler when a single line has been consumed from the input.
 */
class LineListener(onLine: String => Unit) {
  private var buffer = new StringBuilder()

  /** Clear buffered output. */
  def flush(): Unit = {
    if (buffer.length() > 0) {
      onLine(buffer.toString())
      reset()
    }
  }

  def appendBytes(bytes: ByteBuffer): this.type = {
    appendPlainString(toPlainString(bytes))
  }

  def appendString(text: String): this.type = {
    appendPlainString(toPlainString(text))
  }

  private def appendPlainString(text: String): this.type = {
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
