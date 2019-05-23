// This file contains adapted source code from tut, see NOTICE.md for LICENSE.
// Original source: https://github.com/tpolecat/tut/blob/e692c74afe7cb9f144f464b97f100c11367c7dfa/modules/core/src/main/scala/tut/AnsiFilterStream.scala
package scala.meta.internal.ansi

/**
 * A state machine for filtering out ANSI escape codes from a character stream.
 */
case class AnsiStateMachine(
    apply: Int => AnsiStateMachine
)
object AnsiStateMachine {
  val Start: AnsiStateMachine = AnsiStateMachine {
    case 27 => Ansi1
    case 13 => CarriageReturn // \r
    case _ => Print
  }

  val CarriageReturn: AnsiStateMachine = AnsiStateMachine {
    case 10 => LineFeed // drop \r from \r\n
    case _ => Print
  }
  val LineFeed: AnsiStateMachine = AnsiStateMachine(_ => LineFeed)
  val Print: AnsiStateMachine = AnsiStateMachine(_ => Print)
  val Discard: AnsiStateMachine = AnsiStateMachine(_ => Discard)
  val Ansi1: AnsiStateMachine = AnsiStateMachine {
    case '[' => Ansi2
    case _ => Print
  }
  val Ansi2: AnsiStateMachine = AnsiStateMachine {
    case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => Ansi3
    case _ => Print
  }
  val Ansi3: AnsiStateMachine = AnsiStateMachine {
    case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => Ansi3
    case ';' => Ansi2
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
