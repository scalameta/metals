package scala.meta.internal.metals.formatting
import scala.util.matching.Regex

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

case class IndentOnPaste(userConfig: () => UserConfiguration)
    extends RangeFormatter {

  private val indentRegex: Regex = raw"\S".r

  private def codeStartPosition(line: String): Option[Int] =
    indentRegex.findFirstMatchIn(line).map(_.start)

  // converts spaces into tabs and vice-versa, normalizing the lengths of indentations
  private def normalizeSpacesAndTabs(
      line: String,
      opts: FmtOptions,
      firstLineStart: Option[Int] = None,
  ): String = {
    import opts._
    val (prePasted, pastedLine) = line.splitAt(firstLineStart.getOrElse(0))
    codeStartPosition(pastedLine).filter(_ > 0) match {
      case Some(codeStartPos) =>
        val (indentation, code) = pastedLine.splitAt(codeStartPos)
        val pastedBlank = indentation.head
        blank match {
          case _ if pastedBlank == blank => line
          case '\t' if pastedBlank == ' ' =>
            val tabNum = math.ceil(indentation.length.toDouble / 2).toInt
            prePasted + blank.stringRepeat(tabNum) + code
          case ' ' if pastedBlank == '\t' =>
            prePasted + blank.stringRepeat(tabSize * indentation.length) + code
          case _ => line
        }
      case None => line
    }
  }

  override def contribute(
      rangeFormatterParams: RangeFormatterParams
  ): Option[List[TextEdit]] = {
    if (userConfig().enableIndentOnPaste) {
      val formattingOptions = rangeFormatterParams.formattingOptions
      val startPos = rangeFormatterParams.startPos
      val endPos = rangeFormatterParams.endPos
      val splitLines = rangeFormatterParams.splitLines

      val rangeStart = startPos.toLsp.getStart
      val originalStart = rangeStart.getCharacter()
      rangeStart.setCharacter(0)
      // we format full lines even if not everything was pasted
      val realEndColumn =
        if (endPos.endLine < splitLines.size) splitLines(endPos.endLine).size
        else endPos.endColumn
      val pastedRange =
        new Range(rangeStart, new Position(endPos.endLine, realEndColumn))
      val startLine = startPos.toLsp.getStart.getLine
      val endLine = endPos.toLsp.getEnd.getLine

      val opts =
        if (formattingOptions.isInsertSpaces)
          FmtOptions.spaces(formattingOptions.getTabSize)
        else
          FmtOptions.tabs

      val pastedLines = splitLines.slice(startLine, endLine + 1)

      // Do not adjust indentation if we pasted into an existing line content
      def pastedIntoNonEmptyLine = {
        pastedLines match {
          case array if array.length > 0 =>
            val firstLine = array.head
            val originalLine = firstLine.substring(0, originalStart)
            originalLine.trim().nonEmpty
          case _ => false
        }
      }

      if (pastedIntoNonEmptyLine) {
        None
      } else {
        val currentFirstLineIndent = pastedLines.headOption
          .flatMap(codeStartPosition)
          .getOrElse(originalStart)
        val currentIndentationLevel =
          Math.min(originalStart, currentFirstLineIndent)
        val formatted =
          processLines(
            currentIndentationLevel,
            pastedLines,
            opts,
            originalStart,
          )

        if (formatted.nonEmpty)
          Some(
            new TextEdit(
              pastedRange,
              formatted.mkString(System.lineSeparator),
            ) :: Nil
          )
        else
          None
      }
    } else {
      None
    }
  }

  private def processLines(
      expectedIndent: Int,
      lines: Array[String],
      opts: FmtOptions,
      startCharacter: Int,
  ): Array[String] = {

    /*
     * Calculates how much leading whitespace-symbols
     * might be removed from each line.
     * For example, if pasted code was copied with indetation
     * that is larger than it's needed in paste place.
     * ```scala
     * object source:
     *   ...          // some code before
     *      if (cond) // <- copy-paste
     *        fx      // <- these lines
     *        gx      // <- ignoring comments
     *
     * object target:
     *   if (cond) // <- pasted identation is less than original
     *     fx
     *     gx
     * ```
     */
    val converted = lines.zipWithIndex.map {
      case (line, 0) =>
        PastedLine.firstOrEmpty(
          normalizeSpacesAndTabs(line, opts, Some(startCharacter)),
          startCharacter,
        )
      case (line, _) =>
        PastedLine.plainOrEmpty(normalizeSpacesAndTabs(line, opts))
    }
    val indents = converted.collect { case v: PastedLine.NonEmpty =>
      v.pastedIndent
    }

    val overIndent = if (indents.nonEmpty) indents.min else 0

    val idented = converted.map(_.reformat(expectedIndent, overIndent, opts))
    idented
  }

  case class FmtOptions(blank: Char, tabSize: Int)
  object FmtOptions {
    val tabs: FmtOptions = FmtOptions('\t', 1)
    def spaces(tabSize: Int): FmtOptions = FmtOptions(' ', tabSize)
  }

  sealed trait PastedLine {
    def reformat(expectedIdent: Int, overIndent: Int, opts: FmtOptions): String
    final def isEmpty: Boolean = this match {
      case PastedLine.Empty => true
      case _ => false
    }
  }
  object PastedLine {

    case object Empty extends PastedLine {
      def reformat(
          expectedIdent: Int,
          overIndent: Int,
          opts: FmtOptions,
      ): String = ""
    }

    sealed trait NonEmpty extends PastedLine {
      def pastedIndent: Int
    }

    case class FirstLine(
        beforePaste: String,
        pasted: String,
        full: String,
    ) extends NonEmpty {

      val pastedIndent: Int = codeStartPosition(pasted).getOrElse(0)

      def reformat(
          expectedIdent: Int,
          overIndent: Int,
          opts: FmtOptions,
      ): String = {
        val identToStart = codeStartPosition(full).getOrElse(0)

        if (identToStart != expectedIdent) {
          def currentIndent = opts.blank.stringRepeat(identToStart)
          opts.blank.stringRepeat(expectedIdent) + {
            if (identToStart != 0)
              full.stripPrefix(currentIndent)
            else full
          }
        } else {
          full
        }

      }
      override def toString: String =
        s"FirstLine(${beforePaste}@${pasted})"
    }

    case class Plain(line: String) extends NonEmpty {
      val pastedIndent: Int = codeStartPosition(line).getOrElse(0)
      def reformat(expected: Int, overIndent: Int, opts: FmtOptions): String = {
        if (line.trim.isEmpty()) ""
        else
          opts.blank.stringRepeat(expected) + line.substring(
            overIndent,
            line.length,
          )
      }
    }

    def plainOrEmpty(line: String): PastedLine =
      if (line.trim.isEmpty) Empty else Plain(line)

    def firstOrEmpty(line: String, start: Int): PastedLine = {
      if (line.trim.isEmpty) Empty
      else {
        val (beforePaste, pasted) = line.splitAt(start)
        FirstLine(
          beforePaste,
          pasted,
          line,
        )
      }
    }
  }

}
