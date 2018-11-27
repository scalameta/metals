package scala.meta.internal.metals

import scala.meta._

object PositionSyntax {

  def formatMessage(pos: Position, severity: String, message: String): String =
    pos match {
      case Position.None =>
        s"$severity: $message"
      case _ =>
        new java.lang.StringBuilder()
          .append(pos.lineInput)
          .append(if (severity.isEmpty) "" else " ")
          .append(severity)
          .append(
            if (message.isEmpty) ""
            else if (severity.isEmpty) " "
            else if (message.startsWith("\n")) ":"
            else ": "
          )
          .append(message)
          .append("\n")
          .append(pos.lineContent)
          .append("\n")
          .append(pos.lineCaret)
          .toString
    }

  implicit class XtensionPositionsScalafix(private val pos: Position)
      extends AnyVal {

    def contains(other: Position): Boolean = {
      pos.start <= other.start &&
      pos.end >= other.end
    }

    def formatMessage(severity: String, message: String): String =
      PositionSyntax.formatMessage(pos, severity, message)

    /** Returns a formatted string of this position including filename/line/caret. */
    def lineInput: String =
      s"${pos.input.syntax}:${pos.startLine + 1}:${pos.startColumn + 1}:"

    def rangeNumber: String =
      s"${pos.startLine + 1}:${pos.startColumn + 1}..${pos.endLine + 1}:${pos.endColumn + 1}"

    def lineCaret: String = pos match {
      case Position.None =>
        ""
      case _ =>
        val caret =
          if (pos.start == pos.end) "^"
          else if (pos.startLine == pos.endLine) "^" * (pos.end - pos.start)
          else "^"
        (" " * pos.startColumn) + caret
    }

    def lineContent: String = pos match {
      case Position.None => ""
      case range: Position.Range =>
        val pos = Position.Range(
          range.input,
          startLine = range.startLine,
          startColumn = 0,
          endLine = range.startLine,
          endColumn = Int.MaxValue
        )
        pos.text
    }
  }

}
