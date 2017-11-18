package org.langmeta.languageserver

import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import scala.meta.languageserver.{index => i}

object InputEnrichments {
  implicit class XtensionInputOffset(val input: Input) extends AnyVal {
    def fromIndexRange(range: i.Range): Position = {
      toPosition(
        range.startLine,
        range.startColumn,
        range.endLine,
        range.endColumn
      )
    }
    def toIndexRange(start: Int, end: Int): i.Range = {
      val pos = Position.Range(input, start, end)
      i.Range(
        startLine = pos.startLine,
        startColumn = pos.startColumn,
        endLine = pos.endLine,
        endColumn = pos.endColumn
      )
    }

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
