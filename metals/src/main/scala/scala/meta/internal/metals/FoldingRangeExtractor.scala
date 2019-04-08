package scala.meta.internal.metals

import java.util
import org.eclipse.lsp4j.FoldingRange
import scala.annotation.tailrec
import scala.collection.mutable
import scala.meta.Term
import scala.meta._
import scala.meta.internal.metals.FoldingRangeProvider._
import scala.meta.tokens.Token

final class FoldingRangeExtractor(foldOnlyLines: Boolean) {
  def extract(tree: Tree): util.List[FoldingRange] = {
    val ranges = new FoldingRanges(foldOnlyLines)
    extractCommentRanges(tree.tokens, ranges)
    new Traverser(ranges).apply(tree)
    ranges.get
  }

  private final class Traverser(ranges: FoldingRanges) {
    def apply(tree: Tree): Unit = {
      fold(tree)
      traverse(tree)
    }

    private def fold(tree: Tree): Unit = tree match {
      case _: Term.Block =>
        ranges.add(Region, tree.pos)
      case _: Template =>
        ranges.add(Region, tree.pos)

      case loop: Term.For =>
        val forToken = loop.tokens.head
        val startLine = forToken.pos.endLine
        val startColumn = forToken.pos.endColumn // fold everything just after "for"

        val endLine = loop.body.pos.startLine
        val endColumn = loop.body.pos.startColumn // must be exact$startColumn, since it can be "}{"

        val range = new FoldingRange(startLine, endLine)
        range.setStartCharacter(startColumn)
        range.setEndCharacter(endColumn)

        ranges.add(Region, range)

      // it preserves the whitespaces between "yield" token and the body
      case loop: Term.ForYield =>
        val forToken = loop.tokens.head
        val startLine = forToken.pos.endLine
        val startColumn = forToken.pos.endColumn // fold everything just after "for"

        val range = loop.tokens.collectFirst {
          case token: Token.KwYield => // fold up to the 'yield' token
            val endLine = token.pos.startLine
            val endColumn = token.pos.startColumn

            val range = new FoldingRange(startLine, endLine)
            range.setStartCharacter(startColumn)
            range.setEndCharacter(endColumn)
            range
        }

        range.foreach(ranges.add(Region, _))

      case matchTerm: Term.Match =>
        val range = matchTerm.tokens.collectFirst {
          case token: Token.KwMatch => // fold just behind the 'match' token
            val startLine = token.pos.endLine
            val startColumn = token.pos.endColumn

            val range = new FoldingRange(startLine, matchTerm.pos.endLine)
            range.setStartCharacter(startColumn)
            range.setEndCharacter(matchTerm.pos.endColumn)
            range
        }
        range.foreach(ranges.add(Region, _))

      case stmt: Case =>
        val range = stmt.tokens.collectFirst {
          case token: Token.RightArrow =>
            val startLine = token.pos.endLine
            val startColumn = token.pos.endColumn

            val tokens = stmt.tokens
            val lastToken = // every but last case ends on the first column of the new case...
              if (tokens.last.pos.endColumn == 0)
                tokens.dropRight(1).last
              else stmt.tokens.last

            val range = new FoldingRange(startLine, lastToken.pos.endLine)
            range.setStartCharacter(startColumn)
            range.setEndCharacter(lastToken.pos.endColumn)
            range
        }

        range.foreach(ranges.addAsIs(Region, _))
      case _ =>
    }

    private def traverse(tree: Tree): Unit = tree match {
      case stmt: Case if stmt.body.is[Term.Block] =>
        val withoutBody = stmt.children.filter(_ ne stmt.body) // skip body
        traverse(withoutBody)
        traverse(stmt.body)

      case _ => traverse(tree.children)
    }

    private def traverse(trees: Seq[Tree]): Unit = {
      val importGroups = mutable.Buffer(mutable.Buffer[Import]())
      val nonImport = mutable.Buffer[Tree]()

      @tailrec
      def groupImports(seq: Seq[Tree]): Unit =
        seq match {
          case Seq() =>
          case (i: Import) +: tail =>
            importGroups.head += i
            groupImports(tail)
          case tree +: tail =>
            if (importGroups.head.nonEmpty)
              importGroups += mutable.Buffer[Import]()
            nonImport += tree
            groupImports(tail)
        }

      groupImports(trees)

      importGroups.foreach(foldImports)
      nonImport.foreach(apply)
    }

    private def foldImports(imports: Seq[Import]): Unit = imports match {
      case Seq() =>
      case _ +: Seq() =>
      case _ =>
        val firstImportKeywordPos = imports.head.tokens.head.pos
        val lastImportPos = imports.last.pos

        val range =
          new FoldingRange(firstImportKeywordPos.endLine, lastImportPos.endLine)
        range.setStartCharacter(firstImportKeywordPos.endColumn)
        range.setEndCharacter(lastImportPos.endColumn)
        ranges.addAsIs(Imports, range)
    }
  }

  private def extractCommentRanges(
      tokens: Seq[Token],
      ranges: FoldingRanges
  ): Unit = {
    val iterator = tokens.iterator

    @tailrec
    def findLastConsecutiveComment(acc: Token.Comment): Token.Comment =
      if (iterator.hasNext) {
        iterator.next() match {
          case token: Token.Comment => findLastConsecutiveComment(token)
          case _: Token.Space | _: Token.LF => findLastConsecutiveComment(acc)
          case _ => acc
        }
      } else acc

    def isLineComment(comment: Token.Comment): Boolean =
      comment.toString().startsWith("//")

    while (iterator.hasNext) {
      iterator.next() match {
        case first: Token.Comment =>
          val last = findLastConsecutiveComment(first)

          val range = new FoldingRange(first.pos.startLine, last.pos.endLine)
          range.setStartCharacter(first.pos.startColumn)
          range.setEndCharacter(last.pos.endColumn)

          if (isLineComment(last)) {
            if (range.getStartLine < range.getEndLine) { // one line comment should not be folded
              ranges.addAsIs(Comment, range)
            }
          } else {
            ranges.add(Comment, range)
          }
        case _ =>
      }
    }
  }

}
