package scala.meta.internal.ansi

import java.lang.StringBuilder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

import scala.collection.mutable

object LineListener {
  def info: LineListener = new LineListener(line => scribe.info(line))
}

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

  /**
   * Clear buffered output. */
  def flush(): Unit = {
    onLine(buffer.toString())
    buffer = new StringBuilder()
  }

  /**
   * Clear buffered output, if there exists any. */
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
    // NOTE(olafur): this is a hot execution path so the code is not implemented in the most readable
    // or maintainable way for performance reasons. We are streaming the output from build logs
    // to the LSP client, with basic processing like stripping out ANSI escape code that won't
    // render nicely in the editor UI. We could write the same logic with fewer lines of code
    // and a more readable implementation by doing something like:
    // - convert bytes into a string
    // - strip out ansi escape codes from string using fansi
    // - use linesIterator to walk lines
    // Instead, we process the byte-stream directly to filter out unwanted ANSI escape code
    // and \r characters as they are printed from the build tool.
    val chars = StandardCharsets.UTF_8.decode(bytes)
    while (chars.hasRemaining()) {
      val ch = chars.get()
      // Strategy is, accumulate values as long as we're in a non-terminal state, then either discard
      // them if we reach Discard (which means we accumulated an ANSI escape sequence) or print them out if
      // we reach Print.
      state.apply(ch) match {
        case AnsiStateMachine.Print =>
          stack.foreach { i => onCharacter(i) }
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
