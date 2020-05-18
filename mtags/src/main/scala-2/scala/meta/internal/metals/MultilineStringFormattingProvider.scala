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
    while (tokens(methodIndex).isWhiteSpaceOrComment ||
      tokens(methodIndex).isInstanceOf[Token.Dot]) methodIndex += 1
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

  private def fourQuotes(
      splitLines: Array[String],
      position: Position
  ): Boolean = {
    val currentLine = splitLines(position.getLine)
    val pos = position.getCharacter
    val onlyFour = 4
    hasNQuotes(pos - 3, currentLine, onlyFour) && currentLine.count(_ == quote) == onlyFour
  }

  private def hasNQuotes(start: Int, text: String, n: Int): Boolean =
    (start until start + n).forall(i =>
      if (i < 0 || i > text.length) false else text(i) == quote
    )

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
      token: Token
  ): Boolean =
    startPos.startLine >= token.pos.startLine && endPos.endLine <= token.pos.endLine

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

  private def indentOnRangeFormatting(
      start: StartPosition,
      end: EndPosition,
      tokens: Tokens,
      newlineAdded: Boolean,
      splitLines: Array[String],
      range: Range,
      text: String
  ): List[TextEdit] = {
    tokens.zipWithIndex
      .collectFirst {
        isStringOrInterpolation(start, end, text, newlineAdded, tokens)(
          indent(splitLines, start, range)
        )
      }
      .getOrElse(Nil)
  }

  private def isStringOrInterpolation(
      startPosition: StartPosition,
      endPosition: EndPosition,
      sourceText: String,
      newlineAdded: Boolean,
      tokens: Tokens,
      andMultilineString: Boolean = true,
      andHadStripMargin: Boolean = true,
      andHasPipeInScope: Boolean = true
  )(indent: => List[TextEdit]): PartialFunction[(Token, Int), List[TextEdit]] = {
    case (token: Token.Constant.String, index)
        if inToken(startPosition, endPosition, token)
          && (if (andMultilineString) isMultilineString(token) else true)
          && (if (andHadStripMargin) hasStripMarginSuffix(index, tokens)
              else true)
          && (if (andHasPipeInScope)
                pipeInScope(
                  startPosition,
                  endPosition,
                  sourceText,
                  newlineAdded
                )
              else true) =>
      indent
    case (token: Interpolation.Start, index: Int)
        if token.pos.start < startPosition.start && {
          var endIndex = index + 1
          while (!tokens(endIndex)
              .isInstanceOf[Interpolation.End]) endIndex += 1
          val endToken = tokens(endIndex)
          endToken.pos.end > endPosition.end &&
          (if (andMultilineString) isMultilineString(token) else true) &&
          (if (andHadStripMargin) hasStripMarginSuffix(endIndex, tokens)
           else true) &&
          (if (andHasPipeInScope)
             pipeInScope(
               startPosition,
               endPosition,
               sourceText,
               newlineAdded
             )
           else true)
        } =>
      indent
  }

  private def indentTokensOnTypeFormatting(
      start: StartPosition,
      end: EndPosition,
      tokens: Tokens,
      newlineAdded: Boolean,
      splitLines: Array[String],
      position: Position,
      text: String,
      enableStripMargin: Boolean
  ): List[TextEdit] = {
    tokens.zipWithIndex
      .collectFirst {
        isStringOrInterpolation(start, end, text, newlineAdded, tokens)(
          List(indent(splitLines, position))
        ) orElse {
          isStringOrInterpolation(
            start,
            end,
            text,
            newlineAdded,
            tokens,
            andHadStripMargin = false
          ) {
            if (enableStripMargin) {

              if (splitLines(position.getLine - 1).trim.contains("\"\"\"")) {
                val positionOfLastQuote =
                  splitLines(position.getLine).lastIndexOf("\"")
                val newPos =
                  new Position(position.getLine, positionOfLastQuote + 1)
                List(
                  indent(splitLines, position),
                  new TextEdit(new Range(newPos, newPos), ".stripMargin")
                )
              } else Nil
            } else Nil
          }
        }
      }
      .getOrElse(Nil)
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
      if (previousLineNumber > 1 && !splitLines(previousLineNumber - 1).trim.lastOption
          .contains('+'))
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

  private def repaceWithSixQuotes(pos: Position): List[TextEdit] = {
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
    val newlineAdded = params.getCh() == "\n"
    val splitLines = sourceText.split('\n')
    val position = params.getPosition
    withToken(doc, sourceText, range) { (startPos, endPos, tokens) =>
      tokens match {
        case Some(tokens) =>
          indentTokensOnTypeFormatting(
            startPos,
            endPos,
            tokens,
            newlineAdded,
            splitLines,
            position,
            sourceText,
            enableStripMargin
          )
        case None =>
          if (fourQuotes(splitLines, position)) repaceWithSixQuotes(position)
          else if (newlineAdded && doubleQuoteNotClosed(splitLines, position))
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
