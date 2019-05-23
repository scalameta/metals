package scala.meta.internal.ansi

import java.lang.StringBuilder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.CharBuffer
import scala.collection.mutable

/**
 * Converts a stream of strings (with potential ANSI codes and newlines) into a callback for indvidual lines
 * where each individual line has no newline \n characters.
 *
 * @param onLine The callback handler when a single line has been consumed from the input.
 */
class LineListener(onLine: String => Unit) {

  private var buffer = new StringBuilder()
  private var stack = mutable.ListBuffer.empty[Char]
  private var state = AnsiStateMachine.Start

  /** Clear buffered output. */
  def flush(): Unit = {
    onLine(buffer.toString())
    buffer = new StringBuilder()
  }

  /** Clear buffered output, if there exists any. */
  def flushIfNonEmpty(): Unit = {
    if (buffer.length() > 0) {
      flush()
    }
  }

  def appendString(text: String): this.type = {
    appendBytes(
      StandardCharsets.UTF_8.encode(CharBuffer.wrap(text.toCharArray()))
    )
  }

  def appendBytes(bytes: ByteBuffer): this.type = {
    val chars = StandardCharsets.UTF_8.decode(bytes)
    while (chars.hasRemaining()) {
      val ch = chars.get()
      // Strategy is, accumulate values as long as we're in a non-terminal state, then either discard
      // them if we reach Discard (which means we accumulated an ANSI escape sequence) or print them out if
      // we reach Print.
      state.apply(ch) match {
        case AnsiStateMachine.Print =>
          stack.foreach { i =>
            onCharacter(i)
          }
          onCharacter(ch)
          resetState()
        case AnsiStateMachine.Discard =>
          resetState()
        case AnsiStateMachine.LineFeed =>
          flush()
          resetState()
        case other =>
          stack += ch
          state = other
      }
    }
    this
  }

  private def resetState(): Unit = {
    stack.clear()
    state = AnsiStateMachine.Start
  }
  private def onCharacter(char: Char): Unit = {
    if (char == '\n') {
      flush()
    } else {
      buffer.append(char)
    }
  }

}
