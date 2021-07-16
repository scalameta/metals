package scala.meta.internal.ansi

import scala.collection.mutable

import scala.meta.internal.ansi.AnsiStateMachine._

/**
 * Filters out ANSI code from string
 *
 * NOTE(olafur): this is a hot execution path so the code is not implemented in the most readable
 * or maintainable way for performance reasons. We are streaming the output from build logs
 * to the LSP client, with basic processing like stripping out ANSI escape code that won't
 * render nicely in the editor UI. We could write the same logic with fewer lines of code
 * and a more readable implementation by doing something like:
 * - convert bytes into a string
 * - strip out ansi escape codes from string using fansi
 * - use linesIterator to walk lines
 * Instead, we process the chars directly to filter out unwanted ANSI escape code
 */
class AnsiFilter {

  private val stack = mutable.ListBuffer.empty[Char]

  def apply(s: String): String = {
    val builder = new StringBuilder
    var state = Start
    s.foreach { ch =>
      state.apply(ch) match {
        case Print =>
          stack.foreach(builder.append)
          builder.append(ch)
          state = Start
        case Discard =>
          stack.clear()
          state = Start
        case other =>
          stack += ch
          state = other
      }
    }
    stack.clear()
    builder.toString()
  }
}

object AnsiFilter {
  def apply(): AnsiFilter = new AnsiFilter
}
