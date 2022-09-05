package scala.meta.internal.metals.formatting

import scala.annotation.tailrec
import scala.meta

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.tokens.Token
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

private case class StringLiteralExpr(
    input: meta.Input,
    startPos: meta.Position,
    endPos: meta.Position,
    hasStripMargin: Boolean,
)

case class MultilineString(userConfig: () => UserConfiguration)
    extends OnTypeFormatter
    with RangeFormatter {

  private val quote = '"'
  private val space = " "
  private val stripMargin = "stripMargin"
  private val spaceAndBrackets = raw"[ ]*[\}\)\]]+.*"

  private def hasStripMarginSuffix(
      stringTokenIndex: Int,
      tokens: Tokens,
  ): Boolean = {
    var methodIndex = stringTokenIndex + 1
    while (
      tokens(methodIndex).isWhiteSpaceOrComment ||
      tokens(methodIndex).isInstanceOf[Token.Dot]
    ) methodIndex += 1
    tokens(methodIndex) match {
      case token: Token.Ident if token.value == stripMargin =>
        true
      case _ =>
        false
    }
  }

  private def isMultilineString(token: Token): Boolean = {
    val text = token.input.text
    val start = token.start
    hasNQuotes(start, text, 3)
  }

  private def determineDefaultIndent(
      lines: Array[String],
      lineNumberToCheck: Int,
  ): String = {
    val lineToCheck = lines(lineNumberToCheck)
    val index =
      if (lineToCheck.contains("\"\"\"|")) {
        lineToCheck.indexOf('"') + 3
      } else if (lineToCheck.contains("\"\"\"")) {
        lineToCheck.indexOf('"') + 2
      } else lineToCheck.indexOf('|')
    space * index
  }

  private def getIndexOfLastOpenQuote(line: String): Option[(Int, Boolean)] = {

    var lastQuote = -1
    var escaped = false
    var quoteClosed = true
    for (i <- 0 until line.size) {
      val char = line(i)
      if (char == '"') {
        if (!escaped) {
          lastQuote = i
          quoteClosed = !quoteClosed
        } else {
          escaped = !escaped
        }
      } else if (char == '\\') {
        escaped = !escaped
      } else {
        escaped = false
      }
    }
    if (lastQuote != -1) Some((lastQuote, quoteClosed)) else None
  }

  private def getIndexOfLastOpenTripleQuote(
      closedFromPreviousLines: Boolean,
      line: String,
  ): Option[(Int, Boolean)] = {
    var lastTripleQuote = -1
    var tripleQuoteClosed = closedFromPreviousLines
    var quoteNum = 0
    for (i <- 0 until line.size) {
      val char = line(i)
      if (char == '"') {
        quoteNum = quoteNum + 1
        if (quoteNum == 3) {
          lastTripleQuote = i
          tripleQuoteClosed = !tripleQuoteClosed
          quoteNum = 0
        }
      } else {
        quoteNum = 0
      }
    }
    if (lastTripleQuote != -1) Some((lastTripleQuote, tripleQuoteClosed))
    else None
  }

  private def onlyFourQuotes(
      splitLines: Array[String],
      position: Position,
  ): Boolean = {

    val currentLine = splitLines(position.getLine)
    val pos = position.getCharacter
    val onlyFour = 4
    hasNQuotes(pos - 3, currentLine, onlyFour) && currentLine.count(
      _ == quote
    ) == onlyFour
  }

  private def hasNQuotes(start: Int, text: String, n: Int): Boolean =
    (start until start + n).forall(i =>
      if (i < 0 || i >= text.length) false else text(i) == quote
    )

  private def indentWhenNoStripMargin(
      expr: StringLiteralExpr,
      splitLines: Array[String],
      position: Position,
  ): List[TextEdit] = {
    val nextLineIdx = position.getLine + 1
    val enableStripMargin = userConfig().enableStripMarginOnTypeFormatting
    def isLineWithBracket =
      expr.endPos.endLine == nextLineIdx && splitLines(
        nextLineIdx
      ).matches(spaceAndBrackets)
    if (
      enableStripMargin && expr.startPos.startLine == position.getLine - 1 &&
      (expr.endPos.endLine == position.getLine || isLineWithBracket)
    ) {
      val newPos =
        new scala.meta.inputs.Position.Range(
          expr.input,
          expr.endPos.end,
          expr.endPos.end,
        )
      indent(splitLines, position, expr) ++ List(
        new TextEdit(newPos.toLsp, ".stripMargin")
      )
    } else indent(splitLines, position, expr)
  }

  private def indent(
      splitLines: Array[String],
      position: Position,
      expr: StringLiteralExpr,
  ): List[TextEdit] = {
    // position line -1 since we are checking the line before when doing onType
    val defaultIndent = determineDefaultIndent(splitLines, position.getLine - 1)
    val existingSpaces = position.getCharacter
    val addedSpaces = defaultIndent.drop(existingSpaces)
    val startChar = defaultIndent.size - addedSpaces.size
    position.setCharacter(startChar)
    val endChar = startChar + Math.max(0, existingSpaces - defaultIndent.size)
    val endPosition = new Position(position.getLine(), endChar)
    val firstEdit =
      new TextEdit(new Range(position, endPosition), addedSpaces + "|")

    val formatBraces = {
      val nextLineIdx = position.getLine + 1
      val isRangeValid = nextLineIdx <= expr.endPos.endLine
      def isLineWithBracket =
        splitLines(nextLineIdx).matches(spaceAndBrackets)
      if (isRangeValid && isLineWithBracket) {
        val nextLine = splitLines(nextLineIdx)
        val nextLineContent = nextLine.dropWhile(_.isWhitespace)
        val startPos = new Position(nextLineIdx, 0)
        val endPos = new Position(
          nextLineIdx,
          nextLine.size,
        )
        List(
          new TextEdit(
            new Range(startPos, endPos),
            defaultIndent + "|" + nextLineContent,
          )
        )
      } else Nil
    }
    List(firstEdit) ++ formatBraces
  }

  private def indent(
      splitLines: Array[String],
      startPosition: meta.Position,
      range: Range,
  ): List[TextEdit] = {
    // position.startLine since we want to check current line on rangeFormatting
    val defaultIndent =
      determineDefaultIndent(splitLines, startPosition.startLine)
    val linesToFormat =
      range.getStart().getLine().to(range.getEnd().getLine())
    linesToFormat
      .flatMap(line => formatPipeLine(line, splitLines, defaultIndent))
      .toList
  }

  private def inToken(
      startPos: meta.Position,
      endPos: meta.Position,
      startTokenPos: meta.Position,
      endTokenPos: meta.Position,
  ): Boolean =
    startPos.startLine >= startTokenPos.startLine && endPos.endLine <= endTokenPos.endLine

  private def pipeInScope(
      startPos: meta.Position,
      endPos: meta.Position,
      text: String,
      newlineAdded: Boolean,
  ): Boolean = {
    val indexOfLastBackToLine = text.lastIndexBetween(
      '\n',
      0,
      upperBound = startPos.start - 1,
    )
    val lastBackToLine =
      if (!newlineAdded) indexOfLastBackToLine
      else
        text.lastIndexBetween('\n', 0, upperBound = indexOfLastBackToLine - 1)
    val pipeBetweenLastLineAndPos = text.lastIndexBetween(
      '|',
      lastBackToLine,
      startPos.start - 1,
    )
    val pipeBetweenSelection = text.lastIndexBetween(
      '|',
      startPos.start - 1,
      endPos.end - 1,
    )
    pipeBetweenLastLineAndPos != -1 || pipeBetweenSelection != -1
  }

  private def doubleQuoteNotClosed(
      splitLines: Array[String],
      position: Position,
  ): Boolean = {
    val lineBefore = splitLines(position.getLine - 1)
    getIndexOfLastOpenQuote(lineBefore).exists { case (_, quoteClosed) =>
      !quoteClosed
    }
  }

  private def wasTripleQuoted(
      splitLines: Array[String],
      position: Position,
  ): Boolean = {
    var closedFromPreviousLines = true
    var existed = false
    for (i <- 0 until position.getLine()) {
      val currentLine = splitLines(i)
      getIndexOfLastOpenTripleQuote(closedFromPreviousLines, currentLine)
        .foreach { case (_, quoteClosed) =>
          closedFromPreviousLines = quoteClosed
          existed = true
        }
    }
    if (existed)
      !closedFromPreviousLines
    else false
  }

  private def fixStringNewline(
      position: Position,
      splitLines: Array[String],
  ): List[TextEdit] = {
    val previousLineNumber = position.getLine - 1
    val previousLine = splitLines(previousLineNumber)
    val previousLinePosition =
      new Position(previousLineNumber, previousLine.length)
    val textEditPrecedentLine = new TextEdit(
      new Range(previousLinePosition, previousLinePosition),
      "\"" + " " + "+",
    )
    val defaultIndent = previousLine.prefixLength(_ == ' ')
    val indent =
      if (
        previousLineNumber > 1 && !splitLines(
          previousLineNumber - 1
        ).trim.lastOption
          .contains('+')
      )
        defaultIndent + 2
      else defaultIndent
    val interpolationString = getIndexOfLastOpenQuote(previousLine)
      .map { case (lastQuoteIndex, _) =>
        if (lastQuoteIndex > 0 && previousLine(lastQuoteIndex - 1) == 's') "s"
        else ""
      }
      .getOrElse("")

    val zeroPos = new Position(position.getLine, 0)
    val textEditcurrentLine =
      new TextEdit(
        new Range(zeroPos, position),
        " " * indent + interpolationString + "\"",
      )
    List(textEditPrecedentLine, textEditcurrentLine)
  }

  private def replaceWithSixQuotes(pos: Position): List[TextEdit] = {
    val pos1 = new Position(pos.getLine, pos.getCharacter - 3)
    val pos2 = new Position(pos.getLine, pos.getCharacter + 1)
    List(new TextEdit(new Range(pos1, pos2), "\"\"\"\"\"\""))
  }

  private def addTripleQuote(pos: Position): List[TextEdit] = {
    val endPos = new Position(pos.getLine, pos.getCharacter + 1)
    List(new TextEdit(new Range(pos, endPos), "\"\"\""))
  }

  private def formatPipeLine(
      line: Int,
      lines: Array[String],
      defaultIndent: String,
  ): Option[TextEdit] = {
    val zeroPos = new Position(line, 0)
    val lineText = lines(line)
    val firstChar = lineText.trim.headOption
    firstChar match {
      case Some('|') =>
        val firstPipeIndex = lineText.indexOf('|')
        val firstCharAfterPipe = lineText.trim.tail.trim.headOption

        firstCharAfterPipe match {
          case Some('|') =>
            val secondPipeIndex = lineText.indexOf('|', firstPipeIndex + 1)
            val secondPipePos = new Position(line, secondPipeIndex)
            val textEdit =
              new TextEdit(new Range(zeroPos, secondPipePos), defaultIndent)
            Some(textEdit)
          case _ =>
            val pipePos = new Position(line, firstPipeIndex)
            val textEdit =
              new TextEdit(new Range(zeroPos, pipePos), defaultIndent)
            Some(textEdit)
        }
      case _ =>
        val isFirstLineOfMultiLine = lineText.trim.contains("\"\"\"")
        if (isFirstLineOfMultiLine) {
          None
        } else {
          val newText = defaultIndent + "|"
          val textEdit = new TextEdit(new Range(zeroPos, zeroPos), newText)
          Some(textEdit)
        }
    }
  }

  private def collectStringAndInterpolations(
      tokens: Tokens
  ): PartialFunction[(Token, Int), StringLiteralExpr] = {
    case (token: Token.Constant.String, i) if isMultilineString(token) =>
      val hasSuffix = hasStripMarginSuffix(i, tokens)
      StringLiteralExpr(token.input, token.pos, token.pos, hasSuffix)
    case (token: Token.Interpolation.Start, i) if isMultilineString(token) =>
      // interpolations can be nested!
      @tailrec
      def findInterpolationEnd(idx: Int, openedInterpolations: Int): Int = {
        tokens(idx) match {
          case _: Token.Interpolation.Start =>
            findInterpolationEnd(idx + 1, openedInterpolations + 1)
          case _: Token.Interpolation.End =>
            if (openedInterpolations == 1) idx
            else findInterpolationEnd(idx + 1, openedInterpolations - 1)
          case _ => findInterpolationEnd(idx + 1, openedInterpolations)
        }
      }
      val endIndex = findInterpolationEnd(i + 1, 1)
      val endToken = tokens(endIndex)
      val hasSuffix = hasStripMarginSuffix(endIndex, tokens)
      StringLiteralExpr(token.input, token.pos, endToken.pos, hasSuffix)
  }

  private def getStringLiterals(
      tokens: Tokens,
      params: FormatterParams,
      newlineAdded: Boolean,
  ): Iterator[StringLiteralExpr] = {
    val startPos = params.startPos
    val endPos = params.endPos
    val sourceText = params.sourceText
    tokens.toIterator.zipWithIndex
      .collect(collectStringAndInterpolations(tokens))
      .filter { td =>
        inToken(startPos, endPos, td.startPos, td.endPos) &&
        pipeInScope(
          startPos,
          endPos,
          sourceText,
          newlineAdded,
        )
      }
  }

  override def contribute(
      params: OnTypeFormatterParams
  ): Option[List[TextEdit]] = {
    val splitLines = params.splitLines
    val position = params.position
    val triggerChar = params.triggerChar
    (params.tokens, triggerChar) match {
      case (Some(tokens), "\n") =>
        getStringLiterals(tokens, params, true)
          .map { expr =>
            if (expr.hasStripMargin)
              indent(splitLines, position, expr)
            else
              indentWhenNoStripMargin(
                expr,
                splitLines,
                position,
              )
          }
          .find(_.nonEmpty)
      case (None, "\"") if onlyFourQuotes(splitLines, position) =>
        Some(replaceWithSixQuotes(position))
      case (None, "\n")
          if wasTripleQuoted(
            splitLines,
            position,
          ) =>
        Some(addTripleQuote(position))
      case (None, "\n") if doubleQuoteNotClosed(splitLines, position) =>
        Some(fixStringNewline(position, splitLines))

      case _ => None
    }
  }

  override def contribute(
      params: RangeFormatterParams
  ): Option[List[TextEdit]] = {
    val startPos = params.startPos

    params.tokens.flatMap { tokens =>
      getStringLiterals(tokens, params, false)
        .filter(_.hasStripMargin)
        .map { expr =>
          val range = params.range
          val splitLines = params.splitLines
          indent(splitLines, startPos, range)
        }
        .find(_.nonEmpty)
    }
  }
}
