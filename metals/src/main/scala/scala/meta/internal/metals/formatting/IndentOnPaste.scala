package scala.meta.internal.metals.formatting
import scala.util.matching.Regex

import scala.meta.internal.mtags.MtagsEnrichments._

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

object IndentOnPaste extends RangeFormatter {

  private val noEndClause = raw"((<!\bend\b\s*?)\b(if|while|for|match|try))"
  private val ifThenClause = raw"(\bif\s+(?!.*?\bthen\b.*?$$)[^\s]*?)"
  private val keywordClause = raw"\b(then|else|do|catch|finally|yield|case)"
  private val openBraceParenBracketClause =
    raw"""(^.*(\{[^}"']*|\([^)"']*|\[[^]"']*)$$)"""
  private val extensionClause = raw"""extension\s*((\(|\[).*(\)|\]))+"""
  val increaseIndentPatternRegex: Regex =
    raw"""($noEndClause|$ifThenClause|$keywordClause|$extensionClause|=|=>|<-|=>>|:)\s*?$$|$openBraceParenBracketClause""".r
  val indentRegex: Regex = raw"\S".r

  private def codeStartPosition(line: String): Option[Int] =
    indentRegex.findFirstMatchIn(line).map(_.start)

  private def increaseIndentation(line: String) =
    increaseIndentPatternRegex.findFirstIn(line).nonEmpty

  private def stringRepeat(s: Char, n: Int): String =
    ("%0" + n + "d").format(0).replace("0", s.toString)

  // converts spaces into tabs and vice-versa, normalizing the lengths of indentations
  private def normalizeSpacesAndTabs(
      line: String,
      opts: FmtOptions
  ): String = {
    import opts._
    codeStartPosition(line).filter(_ > 0) match {
      case Some(codeStartPos) =>
        val (indentation, code) = line.splitAt(codeStartPos)
        val pastedBlank = indentation.head
        blank match {
          case _ if pastedBlank == blank => line
          case '\t' if pastedBlank == ' ' =>
            val tabNum = math.ceil(indentation.length.toDouble / 2).toInt
            stringRepeat(blank, tabNum) + code
          case ' ' if pastedBlank == '\t' =>
            stringRepeat(blank, tabSize * indentation.length) + code
          case _ => line
        }
      case None => line
    }
  }

  override def contribute(
      rangeFormatterParams: RangeFormatterParams
  ): Option[List[TextEdit]] = {
    val formattingOptions = rangeFormatterParams.formattingOptions
    val startPos = rangeFormatterParams.startPos
    val endPos = rangeFormatterParams.endPos
    val splitLines = rangeFormatterParams.splitLines

    val rangeStart = startPos.toLSP.getStart
    val originalStart = rangeStart.getCharacter()
    rangeStart.setCharacter(0)
    // we format full lines even if not everything was pasted
    val realEndColumn =
      if (endPos.endLine < splitLines.size) splitLines(endPos.endLine).size
      else endPos.endColumn
    val pastedRange =
      new Range(rangeStart, new Position(endPos.endLine, realEndColumn))
    val startLine = startPos.toLSP.getStart.getLine
    val endLine = endPos.toLSP.getEnd.getLine

    val opts =
      if (formattingOptions.isInsertSpaces)
        FmtOptions.spaces(formattingOptions.getTabSize)
      else
        FmtOptions.tabs

    val pastedLines =
      splitLines
        .slice(startLine, endLine + 1)
        .map(normalizeSpacesAndTabs(_, opts))

    // These are the lines from the first pasted line, going above
    val prePastedLines = splitLines.take(startLine).reverse

    // Do not adjust indentation if we pasted into an existing line content
    def pastedIntoNonEmptyLine = {
      pastedLines match {
        case Array(singleLine) =>
          val originalLine = singleLine.substring(0, originalStart) +
            singleLine.substring(endPos.endColumn)
          originalLine.trim().nonEmpty
        case _ => false
      }
    }

    if (pastedIntoNonEmptyLine) {
      None
    } else {
      val currentIndentationLevel = (for {
        line <- prePastedLines.find(t => {
          val trimmed = t.trim()
          trimmed.nonEmpty && !trimmed.startsWith("|")
        }) // Find first line non empty (aka code) that is not a piped multi-string

        indentation <- codeStartPosition(line) // get indentation spaces
        nextIncrease = increaseIndentation(
          line
        ) // check if the next line needs to increase indentation
      } yield {
        if (nextIncrease)
          indentation + opts.tabSize
        else indentation
      }).getOrElse(0)

      val formatted =
        processLines(
          currentIndentationLevel,
          pastedLines,
          opts,
          startPos.toLSP.getStart()
        )

      if (formatted.nonEmpty)
        Some(
          new TextEdit(
            pastedRange,
            formatted.mkString(System.lineSeparator)
          ) :: Nil
        )
      else
        None
    }
  }

  private def processLines(
      expectedIndent: Int,
      lines: Array[String],
      opts: FmtOptions,
      start: Position
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
      case (line, 0) => PastedLine.firstOrEmpty(line, start.getCharacter)
      case (line, _) => PastedLine.plainOrEmpty(line)
    }
    val indents = converted.collect { case v: PastedLine.NonEmpty =>
      v.pastedIndent
    }

    val overIndent = if (indents.nonEmpty) indents.min else 0

    val idented = converted.map(_.reformat(expectedIndent, overIndent, opts))

    // drop leading/trailing empty lines
    val lastIdx = idented.length - 1
    val range = 0 to lastIdx
    val trimmedStart =
      range.dropWhile(converted(_).isEmpty).headOption.getOrElse(0)
    val trimmedEnd = range.reverse
      .dropWhile(converted(_).isEmpty)
      .headOption
      .getOrElse(lastIdx) + 1
    idented.slice(trimmedStart, trimmedEnd)
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
          opts: FmtOptions
      ): String = ""
    }

    sealed trait NonEmpty extends PastedLine {
      def pastedIndent: Int
    }

    case class FirstLine(
        beforePaste: String,
        pasted: String,
        full: String
    ) extends NonEmpty {

      val pastedIndent: Int = codeStartPosition(pasted).getOrElse(0)

      def reformat(
          expectedIdent: Int,
          overIndent: Int,
          opts: FmtOptions
      ): String = {
        val identToStart = codeStartPosition(full).getOrElse(0)

        if (identToStart != expectedIdent) {
          def currentIndent = stringRepeat(opts.blank, identToStart)
          stringRepeat(opts.blank, expectedIdent) + {
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
          stringRepeat(opts.blank, expected) + line.substring(
            overIndent,
            line.length
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
          line
        )
      }
    }
  }

}
