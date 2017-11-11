package org.langmeta.languageserver

import org.langmeta.inputs.Input
import org.langmeta.inputs.Position

object InputEnrichments {
  implicit class XtensionInputOffset(val input: Input) extends AnyVal {

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
