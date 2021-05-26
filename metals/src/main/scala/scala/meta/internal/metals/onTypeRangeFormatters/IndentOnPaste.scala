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
    val inRangeLines = splitLinesWithIndex.filter { case (_, i) =>
      i >= startLine && i <= endLine
    }
    val pastedLines = inRangeLines.map(_._1)
    val pastedLinesWithIndex = pastedLines.zipWithIndex

    val regex =
      raw"(((<!\bend\b\s*?)\b(if|while|for|match|try))|(\bif\s+(?!.*?\bthen\b.*?$$)[^\s]*?)|(\b(then|else|do|catch|finally|yield|case))|=|=>|<-|=>>|:)\s*?$$".r
    val indentRegex = raw"\S".r
    val baseIndent = start.toLSP.getStart.getCharacter

    val (blank, tabSize) =
      if (insertSpaces) (" ", originalTabSize) else ("\t", 1)

    val codeLinesIdxs = (for {
      (lineText, lineIdx) <- pastedLinesWithIndex if lineText.nonEmpty
    } yield lineIdx).toList

    def convertSpaces(line: String, spaceLength: Int): String = {
      if (spaceLength != 0) {
        val indent = line.substring(0, spaceLength)
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
      indentMatchPre <- indentRegex.findFirstMatchIn(pastedLines(secondLineIdx))
      indentLengthPre = indentMatchPre.start
      convertedLines = pastedLines.map(convertSpaces(_, indentLengthPre))
      indentMatch <- indentRegex.findFirstMatchIn(convertedLines(secondLineIdx))
      indentLength = indentMatch.start
      headLine <- convertedLines.headOption
      firstLineMatchesNewIndent = regex.findFirstIn(headLine)
      block = if (firstLineMatchesNewIndent.nonEmpty) 1 else 0
      blockIndent = block * tabSize
    } yield for {
      line <- convertedLines.drop(secondLineIdx)
    } yield {
      val diff = baseIndent + blockIndent - indentLength
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
      head = pastedLines(headIdx)
    } yield head +: newLines

    newLines.map(lines =>
      new TextEdit(range, lines.mkString(util.Properties.lineSeparator)) :: Nil
    )
  }
}
