// This file contains adapted source code from tut, see NOTICE.md for LICENSE.
// Original source: https://github.com/tpolecat/tut/blob/e692c74afe7cb9f144f464b97f100c11367c7dfa/modules/core/src/main/scala/tut/AnsiFilterStream.scala
package scala.meta.internal.ansi

/**
 * A state machine for filtering out ANSI escape codes from a character stream.
 *
 * In addition to stripping out ANSI escape codes, we also strip out carriage return \r
 * characters from Windows-style "\r\n" line breaks.
 */
case class AnsiStateMachine(apply: Int => AnsiStateMachine)
object AnsiStateMachine {
  // Helpful docs on ANSI escape sequences: http://ascii-table.com/ansi-escape-sequences.php
  val Start: AnsiStateMachine = AnsiStateMachine {
    case 27 => AnsiEscape
    case '\r' => CarriageReturn
    case _ => Print
  }

  val CarriageReturn: AnsiStateMachine = AnsiStateMachine {
    case '\n' => LineFeed // drop \r from \r\n
    case _ => Print
  }
  val LineFeed: AnsiStateMachine = AnsiStateMachine(_ => LineFeed)
  val Print: AnsiStateMachine = AnsiStateMachine(_ => Print)
  val Discard: AnsiStateMachine = AnsiStateMachine(_ => Discard)
  val AnsiEscape: AnsiStateMachine = AnsiStateMachine {
    case '[' => AnsiNumericValue
    case _ => Print
  }
  val AnsiNumericValue: AnsiStateMachine = AnsiStateMachine {
    case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => AnsiValue
    case _ => Print
  }
  val AnsiValue: AnsiStateMachine = AnsiStateMachine {
    case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => AnsiValue
    case ';' => AnsiNumericValue
    case '@' | 'A' | 'B' | 'C' | 'D' | 'E' | 'F' | 'G' | 'H' | 'I' | 'J' | 'K' |
        'L' | 'M' | 'N' | 'O' | 'P' | 'Q' | 'R' | 'S' | 'T' | 'U' | 'V' | 'W' |
        'X' | 'Y' | 'Z' | '[' | '\\' | ']' | '^' | '_' | '`' | 'a' | 'b' | 'c' |
        'd' | 'e' | 'f' | 'g' | 'h' | 'i' | 'j' | 'k' | 'l' | 'm' | 'n' | 'o' |
        'p' | 'q' | 'r' | 's' | 't' | 'u' | 'v' | 'w' | 'x' | 'y' | 'z' | '{' |
        '|' | '}' | '~' =>
      Discard // end of ANSI escape
    case _ =>
      Print
  }
}
