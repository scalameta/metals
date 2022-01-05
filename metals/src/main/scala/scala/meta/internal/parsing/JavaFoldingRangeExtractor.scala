package scala.meta.internal.parsing

import scala.annotation.tailrec
import scala.collection.mutable

import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.compiler.ITerminalSymbols
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind

final class JavaFoldingRangeExtractor(
    text: String,
    foldOnlyLines: Boolean
) {
  private val spanThreshold = 2

  private sealed trait Position
  private case class LBrace(line: Int, ch: Int) extends Position
  private case class RBrace(line: Int, ch: Int) extends Position

  private trait Region extends Position {
    def startLine: Int
    def startCh: Int
    def endLine: Int
    def endCh: Int
    def kind: String
  }
  private case class Comment(
      startLine: Int,
      startCh: Int,
      endLine: Int,
      endCh: Int
  ) extends Region {
    def kind = FoldingRangeKind.Comment
  }

  private case class Imports(
      startLine: Int,
      startCh: Int,
      endLine: Int,
      endCh: Int
  ) extends Region {
    def kind = FoldingRangeKind.Imports
  }

  def extract(): List[FoldingRange] = {
    val scanner = ToolFactory.createScanner(true, true, false, true)
    scanner.setSource(text.toCharArray())

    @tailrec
    def swallowUntilSemicolon(token: Int, line: Int): Int = {
      val addLine = scanner.getCurrentTokenSource.count(_ == '\n')
      if (token != ITerminalSymbols.TokenNameSEMICOLON) {
        swallowUntilSemicolon(scanner.getNextToken(), line + addLine)
      } else {
        line + addLine
      }
    }

    case class CurrentScannerStatus(
        currentToken: Int,
        currentLine: Int,
        lastImportLine: Int,
        lastImportCharacter: Int
    )
    @tailrec
    def gatherImportLines(
        token: Int,
        line: Int,
        lastImportLine: Int,
        hasCarriageReturn: Boolean = false
    ): CurrentScannerStatus = {
      val addLine = scanner.getRawTokenSource().count(_ == '\n')
      token match {
        case ITerminalSymbols.TokenNameimport =>
          val currentLine = swallowUntilSemicolon(token, line)
          gatherImportLines(scanner.getNextToken(), currentLine, line)
        case ITerminalSymbols.TokenNameWHITESPACE =>
          gatherImportLines(
            scanner.getNextToken(),
            line + addLine,
            lastImportLine,
            hasCarriageReturn || scanner.getRawTokenSource().contains('\r')
          )
        case _ =>
          val endCharacter =
            scanner.getLineEnd(lastImportLine) -
              scanner.getLineStart(lastImportLine)
          val adjustedEnd =
            if (hasCarriageReturn) endCharacter - 1 else endCharacter
          CurrentScannerStatus(token, line, lastImportLine, adjustedEnd)
      }

    }

    @tailrec
    def gather(token: Int, line: Int, acc: List[Position]): List[Position] = {
      if (token != ITerminalSymbols.TokenNameEOF) {
        val addLine = scanner.getRawTokenSource().count(_ == '\n')
        def next = scanner.getNextToken()
        def currentCharacterStart =
          scanner.getCurrentTokenStartPosition() - scanner.getLineStart(line)
        def currentCharacterEnd =
          scanner.getCurrentTokenEndPosition() -
            scanner.getLineStart(line + addLine)

        token match {
          case ITerminalSymbols.TokenNameLBRACE =>
            val startCharacter =
              if (foldOnlyLines) 0 else currentCharacterStart
            gather(next, line, LBrace(line, startCharacter) :: acc)
          case ITerminalSymbols.TokenNameRBRACE =>
            val (endLine, endChracter) =
              if (foldOnlyLines) (line - 1, 0)
              else (line, currentCharacterEnd + 1)
            gather(next, line, RBrace(endLine, endChracter) :: acc)

          case ITerminalSymbols.TokenNameimport =>
            val startCharacter =
              if (foldOnlyLines) 0 else currentCharacterStart
            val startLine = line
            val CurrentScannerStatus(tk, currentLine, endLine, endCharacter) =
              gatherImportLines(token, line, line)
            val imports =
              Imports(startLine, startCharacter, endLine, endCharacter)
            gather(tk, currentLine, imports :: acc)

          case ITerminalSymbols.TokenNameCOMMENT_BLOCK |
              ITerminalSymbols.TokenNameCOMMENT_JAVADOC =>
            val currentEndLine = line + addLine
            val (startCh, endCh, endLine) =
              if (foldOnlyLines)
                (0, 0, currentEndLine - 1)
              else
                (
                  currentCharacterStart,
                  currentCharacterEnd + 1,
                  currentEndLine
                )
            val comment = Comment(line, startCh, endLine, endCh)
            gather(next, line + addLine, comment :: acc)

          case ITerminalSymbols.TokenNameWHITESPACE =>
            val addLine = scanner.getRawTokenSource().count(_ == '\n')
            gather(next, line + addLine, acc)
          case _ =>
            gather(next, line + addLine, acc)
        }

      } else {
        acc.reverse
      }

    }
    val startAndEnds = gather(scanner.getNextToken(), 1, Nil)
    val stack = mutable.Stack.empty[LBrace]
    @tailrec
    def group(
        remaining: List[Position],
        acc: List[FoldingRange]
    ): List[FoldingRange] = {
      remaining match {
        case (lBrace: LBrace) :: tail =>
          stack.push(lBrace)
          group(tail, acc)
        case RBrace(endLine, endCharacter) :: tail if stack.nonEmpty =>
          val lBrace = stack.pop()
          createFoldingRange(
            lBrace.line - 1,
            lBrace.ch,
            endLine - 1,
            endCharacter,
            FoldingRangeKind.Region
          ) match {
            case Some(newFoldingRange) =>
              group(tail, newFoldingRange :: acc)
            case None =>
              group(tail, acc)
          }

        case (region: Region) :: tail =>
          createFoldingRange(
            region.startLine - 1,
            region.startCh,
            region.endLine - 1,
            region.endCh,
            region.kind
          ) match {
            case Some(newFoldingRange) =>
              group(tail, newFoldingRange :: acc)
            case None =>
              group(tail, acc)
          }

        case _ =>
          acc.reverse
      }

    }
    group(startAndEnds, Nil)
  }

  private def createFoldingRange(
      startLine: Int,
      startCharacter: Int,
      endLine: Int,
      endCharacter: Int,
      kind: String
  ): Option[FoldingRange] = {
    if (endLine - startLine >= spanThreshold) {
      val newFoldingRange = new FoldingRange(startLine, endLine)
      newFoldingRange.setStartCharacter(startCharacter)
      newFoldingRange.setEndCharacter(endCharacter)
      newFoldingRange.setKind(kind)
      Some(newFoldingRange)
    } else {
      None
    }
  }

}
