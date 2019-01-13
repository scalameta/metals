package scala.meta.internal.mtags

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.io.FileIO
import scala.meta.internal.semanticdb.Language
import scala.meta.io.AbsolutePath
import scala.meta.internal.{semanticdb => s}

object MtagsEnrichments {
  implicit class XtensionRange(range: s.Range) {
    def isPoint: Boolean = {
      range.startLine == range.endLine &&
      range.startCharacter == range.endCharacter
    }
    def encloses(other: s.Range): Boolean = {
      range.startLine <= other.startLine &&
      range.endLine >= other.endLine &&
      range.startCharacter <= other.startCharacter && {
        range.endCharacter > other.endCharacter ||
        other == range
      }
    }
  }
  private def filenameToLanguage(filename: String): Language = {
    if (filename.endsWith(".java")) Language.JAVA
    else if (filename.endsWith(".scala")) Language.SCALA
    else Language.UNKNOWN_LANGUAGE
  }
  implicit class XtensionPathMetals(file: Path) {
    def toLanguage: Language = {
      filenameToLanguage(file.getFileName.toString)
    }
  }
  implicit class XtensionAbsolutePathMetals(file: AbsolutePath) {

    def isScalaOrJava: Boolean = {
      toLanguage match {
        case Language.SCALA | Language.JAVA => true
        case _ => false
      }
    }
    def isSemanticdb: Boolean = {
      file.toNIO.getFileName.toString.endsWith(".semanticdb")
    }
    def toLanguage: Language = {
      file.toNIO.toLanguage
    }
    def toInput: Input.VirtualFile = {
      val text = FileIO.slurp(file, StandardCharsets.UTF_8)
      val path = file.toString()
      val input = Input.VirtualFile(path, text)
      input
    }
  }

  implicit class XtensionInputOffset(input: Input) {
    def toLanguage: Language = input match {
      case Input.VirtualFile(path, _) =>
        filenameToLanguage(path)
      case _ =>
        Language.UNKNOWN_LANGUAGE
    }

    /** Returns offset position with end == start == offset */
    def toOffsetPosition(offset: Int): Position =
      Position.Range(input, offset, offset)

    /** Returns an offset for this input */
    def toOffset(line: Int, column: Int): Int =
      input.lineToOffset(line) + column

    /** Returns an offset position for this input */
    def toPosition(startLine: Int, startColumn: Int): Position.Range =
      toPosition(startLine, startColumn, startLine, startColumn)

    def toPosition(occ: s.SymbolOccurrence): Position.Range = {
      val range = occ.range.getOrElse(s.Range())
      toPosition(
        range.startLine,
        range.startCharacter,
        range.endLine,
        range.endCharacter
      )
    }

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
