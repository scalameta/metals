package scala.meta.internal.metals

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.tokens.Token
import scala.meta.tokens.Token.Constant
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

  private def isMultilineString(text: String, token: Token): Boolean = {
    val start = token.start
    text(start) == quote &&
    text(start + 1) == quote &&
    text(start + 2) == quote
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

  private def multilineStringInTokens(
      tokens: Tokens,
      startPos: StartPosition,
      endPos: EndPosition,
      sourceText: String,
      newlineAdded: Boolean
  ): Boolean = {
    if (pipeInScope(startPos, endPos, sourceText, newlineAdded)) {
      tokens.zipWithIndex
        .exists(
          shouldFormatMultiString(sourceText, tokens, startPos, endPos) orElse
            shouldFormatInterpolationString(
              sourceText,
              tokens,
              startPos,
              endPos
            ) orElse {
            case _ => false
          }
        )
    } else false
  }

  private def shouldFormatMultiString(
      sourceText: String,
      tokens: Tokens,
      start: StartPosition,
      end: EndPosition
  ): PartialFunction[(Token, Int), Boolean] = {
    case (token: Constant.String, index: Int) =>
      isMultilineString(sourceText, token) && hasStripMarginSuffix(
        index,
        tokens
      ) && inToken(start, end, token)
  }
  private def shouldFormatInterpolationString(
      sourceText: String,
      tokens: Tokens,
      start: StartPosition,
      endPosition: EndPosition
  ): PartialFunction[(Token, Int), Boolean] = {
    case (token: Interpolation.Start, index: Int)
        if token.pos.start <= start.start => {
      var endIndex = index + 1
      while (!tokens(endIndex)
          .isInstanceOf[Interpolation.End]) endIndex += 1
      isMultilineString(sourceText, token) && hasStripMarginSuffix(
        endIndex,
        tokens
      ) && tokens(endIndex).pos.end > endPosition.end
    }
  }

  private def withToken(
      textId: TextDocumentIdentifier,
      sourceText: String,
      range: Range
  )(
      fn: (
          StartPosition,
          EndPosition,
          String,
          Option[Tokens]
      ) => List[TextEdit]
  ): List[TextEdit] = {
    val source = textId.getUri.toAbsolutePath
    if (source.exists) {
      val virtualFile = Input.VirtualFile(source.toString(), sourceText)
      val startPos = range.getStart.toMeta(virtualFile)
      val endPos = range.getEnd.toMeta(virtualFile)
      val tokens = Trees.defaultDialect(virtualFile).tokenize.toOption
      fn(startPos, endPos, sourceText, tokens)
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
      sourceText: String
  ): List[TextEdit] = {
    val range = new Range(params.getPosition, params.getPosition)
    val doc = params.getTextDocument()
    val newlineAdded = params.getCh() == "\n"
    val splitLines = sourceText.split('\n')
    val position = params.getPosition
    withToken(doc, sourceText, range) {
      (startPos, endPos, sourceText, tokens) =>
        tokens match {
          case Some(tokens) =>
            if (multilineStringInTokens(
                tokens,
                startPos,
                endPos,
                sourceText,
                newlineAdded
              )) {
              List(indent(splitLines, position))
            } else Nil
          case None =>
            if (newlineAdded && doubleQuoteNotClosed(splitLines, position))
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
    withToken(doc, sourceText, range) {
      (startPos, endPos, sourceText, tokens) =>
        val splitLines = sourceText.split('\n')
        // position.startLine since we want to check current line on rangeFormatting
        val defaultIndent =
          determineDefaultIndent(splitLines, startPos.startLine)
        tokens match {
          case Some(tokens) =>
            if (multilineStringInTokens(
                tokens,
                startPos,
                endPos,
                sourceText,
                newlineAdded = false
              )) {
              val linesToFormat =
                range.getStart().getLine().to(range.getEnd().getLine())
              linesToFormat
                .flatMap(line =>
                  formatPipeLine(line, splitLines, defaultIndent)
                )
                .toList
            } else Nil
          case None => Nil
        }
    }
  }

}
