package scala.meta.internal.parsing

import javax.tools.Diagnostic.NOPOS

import scala.annotation.tailrec

import scala.meta.internal.jpc.JavaMetalsCompiler
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.io.AbsolutePath

import com.sun.source.tree.BlockTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.Tree
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object JavaFoldingRangeExtractor {

  val logger: Logger =
    LoggerFactory.getLogger(classOf[JavaFoldingRangeExtractor.type])

  private case class Range(
      startPos: Long,
      endPos: Long,
      kind: String,
  ) {
    def contains(idx: Long): Boolean = startPos <= idx && endPos >= idx

    def toFoldingRange(lineMap: LineMap): FoldingRange = {
      val startLine = lineMap.getLineNumber(startPos) - 1
      val startCharacter = lineMap.getColumnNumber(startPos) - 1
      val endLine = lineMap.getLineNumber(endPos) - 1
      val endCharacter = lineMap.getColumnNumber(endPos) - 1
      val foldingRange = new FoldingRange(startLine.intValue, endLine.intValue)
      foldingRange.setStartCharacter(startCharacter.intValue)
      foldingRange.setEndCharacter(endCharacter.intValue)
      foldingRange.setKind(kind)
      foldingRange
    }
  }
  private class FoldScanner(
      compUnit: CompilationUnitTree,
      sourcePositions: SourcePositions,
      text: String,
  ) extends TreePathScanner[Unit, Unit] {

    var imports: List[Range] = Nil
    var regions: List[Range] = Nil
    var strings: List[Range] = Nil

    override def scan(tree: Tree, p: Unit): Unit = {
      try {
        super.scan(tree, p)
      } catch {
        case e: AssertionError =>
          logger.debug("assertion error in javac", e)
      }
    }

    private def createRange(
        kind: String,
        moveStartTo: Option[Character],
    ): Option[Range] = {
      val treePath = getCurrentPath
      val originalStartPos =
        sourcePositions.getStartPosition(compUnit, treePath.getLeaf)
      val endPos = sourcePositions.getEndPosition(compUnit, treePath.getLeaf)
      if (NOPOS == originalStartPos || NOPOS == endPos)
        None
      else {
        val startPos = moveStartTo match {
          case Some(ch) =>
            var startPos = originalStartPos
            while (
              startPos < text.length &&
              startPos < endPos &&
              text.charAt(startPos.intValue()) != ch
            ) startPos = startPos + 1
            startPos
          case None => originalStartPos
        }
        Some(Range(startPos, endPos, kind))
      }
    }

    override def visitLiteral(tree: LiteralTree, unused: Unit): Unit = {
      // fold triple-quote Strings - needs jdk 15 to test so no test written
      // also used to exclude ranges when searching for comments
      tree.getValue() match {
        case _: String =>
          createRange(FoldingRangeKind.Region, None) match {
            case Some(range) => strings ::= range
            case None => //
          }
        case _ => //
      }
      super.visitLiteral(tree, unused)
    }

    override def visitImport(tree: ImportTree, unused: Unit): Unit = {
      createRange(FoldingRangeKind.Imports, None) match {
        case Some(range) => imports ::= range
        case None => //
      }
      super.visitImport(tree, unused)
    }

    override def visitBlock(tree: BlockTree, unused: Unit): Unit = {
      // `static` blocks don't start at `{` so pos is adjusted
      createRange(FoldingRangeKind.Region, Some('{')) match {
        case Some(range) => regions ::= range
        case None => //
      }
      super.visitBlock(tree, unused)
    }

    override def visitClass(tree: ClassTree, unused: Unit): Unit = {
      // class blocks don't start at `{` so pos is adjusted
      createRange(FoldingRangeKind.Region, Some('{')) match {
        case Some(range) => regions ::= range
        case None => //
      }
      super.visitClass(tree, unused)
    }
  }

  private def getTrees(
      text: String,
      path: AbsolutePath,
  ): Option[(Trees, CompilationUnitTree)] = {
    val source = SourceJavaFileObject.make(text, path.toURI)
    JavaMetalsCompiler.parse(source)
  }

  // search for any comments in file that aren't defined within a String
  private def findComments(
      text: String,
      exclusions: List[Range],
  ): List[Range] = {
    @tailrec
    def findComments(comments: List[Range], idx: Int): List[Range] = {
      val startIdx = text.indexOf("/*", idx)
      if (startIdx == -1)
        comments
      else {
        val (newComments, newIdx) =
          if (exclusions.exists(range => range.contains(startIdx.longValue)))
            (comments, startIdx + 2)
          else {
            val endIdx = text.indexOf("*/", startIdx + 2)
            val range = Range(
              startIdx.longValue,
              endIdx.longValue + 2,
              FoldingRangeKind.Comment,
            )
            (range :: comments, endIdx + 2)
          }
        findComments(newComments, newIdx)
      }
    }

    findComments(Nil, 0)
  }

  def extract(
      text: String,
      path: AbsolutePath,
      foldOnlyLines: Boolean,
      spanThreshold: Int,
  ): List[FoldingRange] = {
    getTrees(text, path) match {
      case Some((trees, root)) =>
        val sourcePositions = trees.getSourcePositions
        val scanner = new FoldScanner(root, sourcePositions, text)
        scanner.scan(root, {})
        val lineMap = root.getLineMap
        // imports are defined as a range per import but should be treated as one range encompassing all imports
        val mergedImports = mergeRanges(
          scanner.imports.map(range => range.toFoldingRange(lineMap))
        ).toList
        // comments are not returned by the scanner so search separately
        val comments = findComments(text, scanner.strings)
        val allRanges = mergedImports :::
          scanner.regions.map(range => range.toFoldingRange(lineMap)) :::
          scanner.strings.map(range => range.toFoldingRange(lineMap)) :::
          comments.map(range => range.toFoldingRange(lineMap))
        val thresholdedRanges = allRanges
          .filter(range =>
            range.getEndLine - range.getStartLine >= spanThreshold
          )
        if (foldOnlyLines)
          adjustForOverlap(thresholdedRanges)
        else
          thresholdedRanges
      case None => Nil
    }
  }

  // Some clients can't cope with ranges overlapping on the same line e.g. `} else {` would be an end and start.
  // Adjust these to end a line earlier
  private def adjustForOverlap(
      ranges: List[FoldingRange]
  ): List[FoldingRange] = {
    val startLines = ranges.map(_.getStartLine()).distinct.toSet
    ranges.map(range =>
      if (
        startLines.contains(range.getEndLine) && range.getEndLine() > range
          .getStartLine()
      ) {
        val adjustedRange =
          new FoldingRange(range.getStartLine, range.getEndLine - 1)
        adjustedRange.setStartCharacter(range.getStartCharacter)
        val endChar =
          if (
            adjustedRange.getStartLine() == adjustedRange.getEndLine() &&
            range.getStartCharacter() > range.getEndCharacter()
          )
            range.getStartCharacter()
          else
            range.getEndCharacter()
        adjustedRange.setEndCharacter(endChar)
        adjustedRange.setKind(range.getKind)
        adjustedRange
      } else range
    )
  }

  private def mergeRanges(ranges: List[FoldingRange]): Option[FoldingRange] = {
    def minStart(a: FoldingRange, b: FoldingRange): FoldingRange = {
      if (
        a.getStartLine < b.getStartLine || (a.getStartLine == b.getStartLine && a.getStartCharacter < b.getStartCharacter)
      ) a
      else b
    }
    def maxEnd(a: FoldingRange, b: FoldingRange): FoldingRange = {
      if (
        a.getEndLine > b.getEndLine || (a.getEndLine == b.getEndLine && a.getEndCharacter > b.getEndCharacter)
      ) a
      else b
    }
    if (ranges.isEmpty)
      None
    else {
      val min = ranges.reduce(minStart)
      val max = ranges.reduce(maxEnd)

      val mergedRange = new FoldingRange(min.getStartLine, max.getEndLine)
      mergedRange.setStartCharacter(min.getStartCharacter)
      mergedRange.setEndCharacter(max.getEndCharacter)
      mergedRange.setKind(min.getKind)
      Some(mergedRange)
    }
  }
}
