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

  private sealed trait Token
  private case class LBrace(line: Int, ch: Int) extends Token
  private case class RBrace(line: Int, ch: Int) extends Token
  private case class Comment(
      startLine: Int,
      startCh: Int,
      endLine: Int,
      endCh: Int
  ) extends Token

  def extract(): List[FoldingRange] = {
    val scanner = ToolFactory.createScanner(true, true, false, true)
    scanner.setSource(text.toCharArray())
    @tailrec
    def loop(token: Int, line: Int, acc: List[Token]): List[Token] = {
      if (token != ITerminalSymbols.TokenNameEOF) {
        val addLine = scanner.getRawTokenSource().count(_ == '\n')
        def next = scanner.getNextToken()
        def startCharacter = {
          if (foldOnlyLines) 0
          else
            scanner.getCurrentTokenStartPosition() - scanner.getLineStart(line)
        }
        def endCharacter = {
          if (foldOnlyLines) 0
          else
            scanner.getCurrentTokenEndPosition() - scanner.getLineStart(
              line + addLine
            )
        }
        token match {
          case ITerminalSymbols.TokenNameLBRACE =>
            val ch = startCharacter
            loop(next, line, LBrace(line, ch) :: acc)
          case ITerminalSymbols.TokenNameRBRACE =>
            val ch = startCharacter
            loop(next, line, RBrace(line, ch) :: acc)

          case ITerminalSymbols.TokenNameCOMMENT_BLOCK |
              ITerminalSymbols.TokenNameCOMMENT_JAVADOC =>
            val startCh = startCharacter
            val endCh = endCharacter
            val comment = Comment(line, startCh, line + addLine, endCh)
            loop(next, line + addLine, comment :: acc)

          case ITerminalSymbols.TokenNameWHITESPACE =>
            val addLine = scanner.getRawTokenSource().count(_ == '\n')
            loop(next, line + addLine, acc)
          case _ =>
            loop(next, line + addLine, acc)
        }

      } else {
        acc.reverse
      }

    }
    val startAndEnds = loop(scanner.getNextToken(), 0, Nil)
    val stack = mutable.Stack.empty[LBrace]
    @tailrec
    def group(
        remaining: List[Token],
        acc: List[FoldingRange]
    ): List[FoldingRange] = {
      remaining match {
        case (lBrace: LBrace) :: tail =>
          stack.push(lBrace)
          group(tail, acc)
        case RBrace(endLine, endCharacter) :: tail if stack.nonEmpty =>
          val lBrace = stack.pop()
          createFoldingRange(
            lBrace.line,
            lBrace.ch,
            endLine,
            endCharacter,
            FoldingRangeKind.Region
          ) match {
            case Some(newFoldingRange) =>
              group(tail, newFoldingRange :: acc)
            case None =>
              group(tail, acc)
          }
        case Comment(
              startLine,
              startCharacter,
              endLine,
              endCharacter
            ) :: tail =>
          createFoldingRange(
            startLine,
            startCharacter,
            endLine,
            endCharacter,
            FoldingRangeKind.Comment
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
    if (endLine - startLine > spanThreshold) {
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
