package scala.meta.internal.metals.onTypeRangeFormatters
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

case class IndentOnPaste() extends OnTypeRangeFormatter {

  override def onRangeContribute(
      sourceText: String,
      range: lsp4j.Range,
      splitLines: Array[String],
      startPos: StartPosition,
      endPos: EndPosition,
      formattingOptions: FormattingOptions,
      tokens: Option[Tokens]
  ): Option[List[TextEdit]] = {

    val insertSpaces = formattingOptions.isInsertSpaces
    val originalTabSize = formattingOptions.getTabSize

    val start = startPos
    val end = endPos
    val rangeStart = start.toLSP.getStart
    rangeStart.setCharacter(0)

    val range = new Range(rangeStart, end.toLSP.getEnd)

    val startLine = start.toLSP.getStart.getLine
    val endLine = end.toLSP.getEnd.getLine

    val splitLinesWithIndex = splitLines.zipWithIndex
    val inRangeLines = splitLinesWithIndex.slice(startLine, endLine + 1)
    val pastedLines = inRangeLines.map(_._1)
    val pastedLinesWithIndex = pastedLines.zipWithIndex

    val increaseIndentPattern =
      raw"""(((<!\bend\b\s*?)\b(if|while|for|match|try))|(\bif\s+(?!.*?\bthen\b.*?$$)[^\s]*?)|(\b(then|else|do|catch|finally|yield|case))|=|=>|<-|=>>|:)\s*?$$|(^.*\{[^}"']*$$)""".r
    val decreaseIndentPattern =
      raw"""((^\s*end\b\s*)\b(if|while|for|match|try|\w+)$$|(^(.*\*/)?\s*}.*)$$)""".r
    val indentRegex = raw"\S".r

    def indentationEndIndex(line: String): Option[Int] =
      indentRegex.findFirstMatchIn(line).map(_.start)

    def increaseIndentation(line: String) =
      increaseIndentPattern.findFirstIn(line).nonEmpty
    def decreaseIndentation(line: String) =
      decreaseIndentPattern.findFirstIn(line).nonEmpty

    val (blank, tabSize) =
      if (insertSpaces) (" ", originalTabSize) else ("\t", 1)

    // These are the lines from the first pasted line, going above
    val invertedAboveRangeLines = splitLinesWithIndex.take(startLine).reverse
    val decreaseIndentLineIdx = invertedAboveRangeLines
      .find(t => decreaseIndentation(t._1))
      .map(_._2)
      .getOrElse(-1)

    val firstPastedLineIndent = (for {
      (line, idx) <- invertedAboveRangeLines.find(t =>
        increaseIndentation(t._1)
      )
      indentIdx <- indentationEndIndex(line)
    } yield {
      if (decreaseIndentLineIdx > idx && decreaseIndentLineIdx != -1)
        indentIdx
      else
        indentIdx + tabSize
    }).getOrElse(0)

    val codeLinesIdxs = (for {
      (lineText, lineIdx) <- pastedLinesWithIndex if lineText.trim().nonEmpty
    } yield lineIdx).toList

    def convertSpaces(line: String, spaceLength: Int): String = {
      if (spaceLength != 0) {
        val substrLength = math.min(line.length, spaceLength)
        val indent = line.substring(0, substrLength)
        val indentChars = indent.split("")
        val indentChar = indentChars.head
        blank match {
          case "\t" if indentChar == blank => line
          case " " if indentChar == blank => line
          case "\t" if indentChar == " " =>
            val tabNum = math.ceil(indentChar.length / 2).toInt
            blank.repeat(tabNum) ++ line.slice(spaceLength, line.length)
          case " " if indentChar == "\t" =>
            blank.repeat(tabSize) ++ line.slice(spaceLength, line.length)
          case _ => line
        }
      } else line
    }

    val newLinesOpt = for {
      secondLineIdx <- codeLinesIdxs.drop(1).headOption
      indentLengthPreConversion <- indentationEndIndex(
        pastedLines(secondLineIdx)
      )
      convertedLines = pastedLines.map(
        convertSpaces(_, indentLengthPreConversion)
      )
      indentLength <- indentationEndIndex(convertedLines(secondLineIdx))
      headIdx <- codeLinesIdxs.headOption
      headLine = convertedLines(headIdx)
      indentTailLines = increaseIndentation(headLine)
      block = if (indentTailLines) 1 else 0
      blockIndent = block * tabSize
    } yield for {
      line <- convertedLines.drop(headIdx + 1)
    } yield {
      val diff = firstPastedLineIndent + blockIndent - indentLength
      if (diff != 0)
        if (diff < 0)
          line.slice(-diff, line.length)
        else
          blank.repeat(diff) ++ line
      else line
    }

    val newLines = for {
      newLines <- newLinesOpt
      headIdx <- codeLinesIdxs.headOption
      head = blank * firstPastedLineIndent ++ pastedLines(headIdx).trim()
    } yield head +: newLines

    newLines.map(lines =>
      new TextEdit(range, lines.mkString(util.Properties.lineSeparator)) :: Nil
    )
  }
}
