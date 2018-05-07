package org.langmeta.languageserver

import org.langmeta.{lsp => l}
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import scala.meta.internal.semanticdb3

object InputEnrichments {
  implicit class XtensionPositionOffset(val pos: Position) extends AnyVal {
    def caret: String = pos match {
      case Position.None => "<none>"
      case _ => " " * pos.startColumn + "^"
    }
    def path: String = pos match {
      case Position.None => "<none>"
      case _ => s"${pos.input.syntax}:${pos.startLine + 1}:${pos.startColumn}"
    }
    def lineContent: String = pos match {
      case r: Position.Range =>
        val start = pos.start - pos.startColumn
        val end = pos.input.lineToOffset(pos.startLine + 1) - 1
        r.copy(start = start, end = end).text
      case _ => "<none>"
    }
  }
  implicit class XtensionInputOffset(val input: Input) extends AnyVal {
    def toIndexRange(r: semanticdb3.Range): l.Range = {
      l.Range(
        startLine = r.startLine,
        startColumn = r.startCharacter,
        endLine = r.endLine,
        endColumn = r.endCharacter
      )
    }

    /** Returns offset position with end == start == offset */
    def toOffsetPosition(offset: Int): Position =
      Position.Range(input, offset, offset)

    /** Returns a scala.meta.Position from an index range. */
    def toPosition(range: l.Range): Position = {
      toPosition(
        range.start.line,
        range.start.character,
        range.end.line,
        range.end.character
      )
    }

    def toOffset(pos: l.Position): Int =
      toOffset(pos.line, pos.character)

    /** Returns an offset for this input */
    def toOffset(line: Int, column: Int): Int =
      input.lineToOffset(line) + column

    /** Returns an offset position for this input */
    def toPosition(startLine: Int, startColumn: Int): Position.Range =
      toPosition(startLine, startColumn, startLine, startColumn)

    /** Returns a range position for this input */
    def toPosition(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ): Position.Range =
      Position.Range(
        input,
        toOffset(startLine, startColumn),
        toOffset(endLine, endColumn)
      )
  }
}
