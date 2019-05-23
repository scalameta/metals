// This file contains adapted source code from tut, see NOTICE.md for LICENSE.
// Original source: https://github.com/tpolecat/tut/blob/e692c74afe7cb9f144f464b97f100c11367c7dfa/modules/core/src/main/scala/tut/AnsiFilterStream.scala
package scala.meta.internal.metals

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

  /** Clear buffered output. */
  def flush(): Unit = {
    if (buffer.length() > 0) {
      onLine(buffer.toString())
      buffer = new StringBuilder()
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
      // them if we reach T (which means we accumulated an ANSI escape sequence) or print them out if
      // we reach F.
      state.apply(ch) match {
        case F =>
          stack.foreach { i =>
            onCharacter(i)
          }
          onCharacter(ch)
          resetState()
        case T =>
          resetState()
        case L =>
          flush()
          resetState()
        case s =>
          stack += ch
          state = s
      }
    }
    this
  }

  private def onCharacter(char: Char): Unit = {
    if (char == '\n') {
      flush()
    } else {
      buffer.append(char)
    }
  }

  case class State(apply: Int => State)

  val S: State = State {
    case 27 => I0
    case 13 => R // \r
    case _ => F
  }

  val R: State = State {
    case 10 => L // drop \r from \r\n
    case _ => F
  }
  val L: State = State(_ => L)

  val F: State = State(_ => F)

  val T: State = State(_ => T)

  val I0: State = State {
    case '[' => I1
    case _ => F
  }

  val I1: State = State {
    case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => I2
    case _ => F
  }

  val I2: State = State {
    case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => I2
    case ';' => I1
    case '@' | 'A' | 'B' | 'C' | 'D' | 'E' | 'F' | 'G' | 'H' | 'I' | 'J' | 'K' |
        'L' | 'M' | 'N' | 'O' | 'P' | 'Q' | 'R' | 'S' | 'T' | 'U' | 'V' | 'W' |
        'X' | 'Y' | 'Z' | '[' | '\\' | ']' | '^' | '_' | '`' | 'a' | 'b' | 'c' |
        'd' | 'e' | 'f' | 'g' | 'h' | 'i' | 'j' | 'k' | 'l' | 'm' | 'n' | 'o' |
        'p' | 'q' | 'r' | 's' | 't' | 'u' | 'v' | 'w' | 'x' | 'y' | 'z' | '{' |
        '|' | '}' | '~' =>
      T // end of ANSI escape
    case _ => F
  }

  private var stack = mutable.ListBuffer.empty[Char]
  private var state: State = S // Start
  def resetState(): Unit = {
    stack.clear()
    state = S
  }

}
