package scala.meta.internal.metals

import java.util
import org.eclipse.lsp4j.FoldingRange
import scala.meta.Term
import scala.meta._
import scala.meta.internal.metals.FoldingRangeProvider.Region

final class FoldingRangeExtractor(foldOnlyLines: Boolean) {
  def extract(tree: Tree): util.List[FoldingRange] = {
    val ranges = new FoldingRanges(foldOnlyLines)
    new Traverser(ranges).apply(tree)
    ranges.get
  }

  private final class Traverser(ranges: FoldingRanges) {
    def apply(tree: Tree): Unit = {
      fold(tree)
      traverse(tree)
    }

    def fold(tree: Tree): Unit = tree match {
      case _: Term.Block =>
        ranges.add(Region, tree.pos)
      case _: Template =>
        ranges.add(Region, tree.pos)

      case loop: Term.For =>
        val startLine = loop.pos.startLine
        val startColumn = loop.pos.startColumn + 3 // just after "for" since there may be no whitespace (e.g. "for{")

        val endLine = loop.body.pos.startLine
        val endColumn = loop.body.pos.startColumn // must be exact$startColumn, since it can be "}{"

        val range = new FoldingRange(startLine, endLine)
        range.setStartCharacter(startColumn)
        range.setEndCharacter(endColumn)

        ranges.add(Region, range)

      // it preserves the whitespaces between "yield" token and the body
      case loop: Term.ForYield =>
        val startLine = loop.pos.startLine
        val startColumn = loop.pos.startColumn + 3 // just after "for" since there may be no whitespace (e.g. "for{")

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

            // TODO 1: simplify
            val lastToken = // every but last case ends on the first column of the new case...
              if (stmt.tokens.last.pos.endColumn == 0)
                stmt.tokens.dropRight(1).last
              else stmt.tokens.last

            val range = new FoldingRange(startLine, lastToken.pos.endLine)
            range.setStartCharacter(startColumn)
            range.setEndCharacter(lastToken.pos.endColumn)
            range
        }

        range.foreach(ranges.addAsIs(Region, _))
      case _ =>
    }

    def traverse(tree: Tree): Unit = tree match {
      case stmt: Case if stmt.body.is[Term.Block] =>
        stmt.children.filter(_ ne stmt.body).foreach(apply) // skip body
        traverse(stmt.body)

      case _ => tree.children.foreach(apply)
    }
  }
}
