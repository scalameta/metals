package scala.meta.internal.metals

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.tokens.Token
import scala.meta.tokens.Token.Interpolation
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

object MultilineStringFormattingProvider {

  private val quote = '"'
  private val space = " "
  private val stripMargin = "stripMargin"

  type StartPosition = meta.Position
  type EndPosition = meta.Position

  private def hasStripMarginSuffix(
      stringTokenIndex: Int,
      tokens: Tokens
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
      lineNumberToCheck: Int
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

  private def getIndexOfLastQuote(line: String): Option[(Int, Boolean)] = {
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

  private def onlyFourQuotes(
      splitLines: Array[String],
      position: Position
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
      if (i < 0 || i > text.length) false else text(i) == quote
    )

  private def indentWhenNoStripMargin(
      startToken: Token,
      endToken: Token,
      splitLines: Array[String],
      position: Position,
      enableStripMargin: Boolean
  ): List[TextEdit] = {
    if (
      enableStripMargin && startToken.pos.startLine == position.getLine - 1 && endToken.pos.endLine == position.getLine
    ) {
      val newPos =
        new scala.meta.inputs.Position.Range(
          endToken.input,
          endToken.end,
          endToken.end
        )
      List(
        indent(splitLines, position),
        new TextEdit(newPos.toLSP, ".stripMargin")
      )
    } else List(indent(splitLines, position))
  }

  private def indent(
      splitLines: Array[String],
      position: Position
  ): TextEdit = {
    // position line -1 since we are checking the line before when doing onType
    val defaultIndent = determineDefaultIndent(splitLines, position.getLine - 1)
    val existingSpaces = position.getCharacter()
    val addedSpaces = defaultIndent.drop(existingSpaces)
    val startChar = defaultIndent.size - addedSpaces.size
    position.setCharacter(startChar)
    val endChar = startChar + Math.max(0, existingSpaces - defaultIndent.size)
    val endPosition = new Position(position.getLine(), endChar)
    new TextEdit(new Range(position, endPosition), addedSpaces + "|")
  }

  private def indent(
      splitLines: Array[String],
      startPosition: StartPosition,
      range: Range
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
      startToken: Token,
      endToken: Token
  ): Boolean =
    startPos.startLine >= startToken.pos.startLine && endPos.endLine <= endToken.pos.endLine

  private def pipeInScope(
      startPos: meta.Position,
      endPos: meta.Position,
      text: String,
      newlineAdded: Boolean
  ): Boolean = {
    val indexOfLastBackToLine = text.lastIndexBetween(
      '\n',
      0,
      upperBound = startPos.start - 1
    )
    val lastBackToLine =
      if (!newlineAdded) indexOfLastBackToLine
      else
        text.lastIndexBetween('\n', 0, upperBound = indexOfLastBackToLine - 1)
    val pipeBetweenLastLineAndPos = text.lastIndexBetween(
      '|',
      lastBackToLine,
      startPos.start - 1
    )
    val pipeBetweenSelection = text.lastIndexBetween(
      '|',
      startPos.start - 1,
      endPos.end - 1
    )
    pipeBetweenLastLineAndPos != -1 || pipeBetweenSelection != -1
  }

  def isStringOrInterpolation(
      startPosition: StartPosition,
      endPosition: EndPosition,
      token: Token,
      index: Int,
      text: String,
      tokens: Tokens,
      newlineAdded: Boolean
  )(
      indent: (Token, Token, Int) => Option[List[TextEdit]]
  ): Option[List[TextEdit]] = {
    token match {
      case startToken @ (_: Token.Constant.String | _: Interpolation.Start) =>
        val endIndex = if (startToken.isInstanceOf[Interpolation.Start]) {
          var endIndex = index + 1
          while (
            !tokens(endIndex)
              .isInstanceOf[Interpolation.End]
          ) endIndex += 1
          endIndex
        } else index
        val endToken = tokens(endIndex)
        if (
          inToken(startPosition, endPosition, startToken, endToken)
          && isMultilineString(startToken) &&
          pipeInScope(
            startPosition,
            endPosition,
            text,
            newlineAdded
          )
        ) indent(startToken, endToken, endIndex)
        else None
      case _ => None
    }
  }

  private def indentOnRangeFormatting(
      start: StartPosition,
      end: EndPosition,
      tokens: Tokens,
      newlineAdded: Boolean,
      splitLines: Array[String],
      range: Range,
      text: String
  ): List[TextEdit] = {
    tokens.toIterator.zipWithIndex
      .flatMap {
        case (token, index) =>
          isStringOrInterpolation(
            start,
            end,
            token,
            index,
            text,
            tokens,
            newlineAdded
          ) { (_, _, endIndex) =>
            if (hasStripMarginSuffix(endIndex, tokens))
              Some(indent(splitLines, start, range))
            else None
          }
      }
      .headOption
      .getOrElse(Nil)
  }

  private def indentTokensOnTypeFormatting(
      startPosition: StartPosition,
      endPosition: EndPosition,
      tokens: Tokens,
      triggerChar: String,
      splitLines: Array[String],
      position: Position,
      text: String,
      enableStripMargin: Boolean
  ): List[TextEdit] = {
    if (triggerChar == "\n") {
      tokens.toIterator.zipWithIndex
        .flatMap {
          case (token, index) =>
            isStringOrInterpolation(
              startPosition,
              endPosition,
              token,
              index,
              text,
              tokens,
              newlineAdded = true
            ) { (startToken, endToken, endIndex) =>
              if (hasStripMarginSuffix(endIndex, tokens))
                Some(List(indent(splitLines, position)))
              else
                Some(
                  indentWhenNoStripMargin(
                    startToken,
                    endToken,
                    splitLines,
                    position,
                    enableStripMargin
                  )
                )
            }
        }
        .headOption
        .getOrElse(Nil)
    } else Nil
  }

  private def withToken(
      textId: TextDocumentIdentifier,
      sourceText: String,
      range: Range
  )(
      fn: (
          StartPosition,
          EndPosition,
          Option[Tokens]
      ) => List[TextEdit]
  ): List[TextEdit] = {
    val source = textId.getUri.toAbsolutePath
    if (source.exists) {
      val virtualFile = Input.VirtualFile(source.toString(), sourceText)
      val startPos = range.getStart.toMeta(virtualFile)
      val endPos = range.getEnd.toMeta(virtualFile)
      val tokens = Trees.defaultDialect(virtualFile).tokenize.toOption
      fn(startPos, endPos, tokens)
    } else Nil
  }

  private def doubleQuoteNotClosed(
      splitLines: Array[String],
      position: Position
  ): Boolean = {
    val lineBefore = splitLines(position.getLine - 1)
    getIndexOfLastQuote(lineBefore).exists {
      case (_, quoteClosed) => !quoteClosed
    }
  }

  private def fixStringNewline(
      position: Position,
      splitLines: Array[String]
  ): List[TextEdit] = {
    val previousLineNumber = position.getLine - 1
    val previousLine = splitLines(previousLineNumber)
    val previousLinePosition =
      new Position(previousLineNumber, previousLine.length)
    val textEditPrecedentLine = new TextEdit(
      new Range(previousLinePosition, previousLinePosition),
      "\"" + " " + "+"
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
    val interpolationString = getIndexOfLastQuote(previousLine)
      .map {
        case (lastQuoteIndex, _) =>
          if (lastQuoteIndex > 0 && previousLine(lastQuoteIndex - 1) == 's') "s"
          else ""
      }
      .getOrElse("")

    val zeroPos = new Position(position.getLine, 0)
    val textEditcurrentLine =
      new TextEdit(
        new Range(zeroPos, position),
        " " * indent + interpolationString + "\""
      )
    List(textEditPrecedentLine, textEditcurrentLine)
  }

  private def replaceWithSixQuotes(pos: Position): List[TextEdit] = {
    val pos1 = new Position(pos.getLine, pos.getCharacter - 3)
    val pos2 = new Position(pos.getLine, pos.getCharacter + 1)
    List(new TextEdit(new Range(pos1, pos2), "\"\"\"\"\"\""))
  }

  private def formatPipeLine(
      line: Int,
      lines: Array[String],
      defaultIndent: String
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

  def format(
      params: DocumentOnTypeFormattingParams,
      sourceText: String,
      enableStripMargin: Boolean
  ): List[TextEdit] = {
    val range = new Range(params.getPosition, params.getPosition)
    val doc = params.getTextDocument()
    val triggerChar = params.getCh
    val splitLines = sourceText.split('\n')
    val position = params.getPosition
    withToken(doc, sourceText, range) { (startPos, endPos, tokens) =>
      tokens match {
        case Some(tokens) =>
          indentTokensOnTypeFormatting(
            startPos,
            endPos,
            tokens,
            triggerChar,
            splitLines,
            position,
            sourceText,
            enableStripMargin
          )
        case None =>
          if (triggerChar == "\"" && onlyFourQuotes(splitLines, position))
            replaceWithSixQuotes(position)
          else if (
            triggerChar == "\n" && doubleQuoteNotClosed(
              splitLines,
              position
            )
          )
            fixStringNewline(position, splitLines)
          else Nil
      }
    }
  }

  def format(
      params: DocumentRangeFormattingParams,
      sourceText: String
  ): List[TextEdit] = {
    val range = params.getRange()
    val doc = params.getTextDocument()
    val splitLines = sourceText.split('\n')
    withToken(doc, sourceText, range) { (startPos, endPos, tokens) =>
      tokens match {
        case Some(tokens) =>
          indentOnRangeFormatting(
            startPos,
            endPos,
            tokens,
            false,
            splitLines,
            range,
            sourceText
          )
        case None => Nil
      }
    }
  }

}
