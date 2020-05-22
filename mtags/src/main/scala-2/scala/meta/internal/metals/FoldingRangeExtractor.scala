package scala.meta.internal.metals

import java.util

import scala.annotation.tailrec
import scala.collection.mutable

import scala.meta._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.tokens.Token
import scala.meta.tokens.Token.KwMatch

import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind._
import org.eclipse.{lsp4j => l}

final class FoldingRangeExtractor(
    distance: TokenEditDistance,
    foldOnlyLines: Boolean
) {
  private val spanThreshold = 3

  private val ranges = new FoldingRanges(foldOnlyLines)

  def extract(tree: Tree): util.List[FoldingRange] = {
    extractCommentRanges(tree.tokens.iterator, ranges)
    extractFrom(tree, Position.None)
    ranges.get
  }

  def extractFrom(tree: Tree, enclosing: Position): Unit = {
    if (span(tree.pos) > spanThreshold) {
      val newEnclosing = tree match {
        case Foldable(pos) if span(enclosing) - span(pos) > spanThreshold =>
          distance.toRevised(pos.toLSP) match {
            case Some(revisedPos) =>
              val range = createRange(revisedPos)
              ranges.add(Region, range)
              pos
            case None => enclosing
          }
        case _ =>
          enclosing
      }

      val (importGroups, otherChildren) = extractImports(tree.children)
      importGroups.foreach(group => foldImports(group))

      otherChildren.foreach(child => {
        val childEnclosing =
          if (newEnclosing.contains(child.pos)) newEnclosing
          else enclosing

        extractFrom(child, childEnclosing)
      })
    }
  }

  private def extractImports(trees: List[Tree]) = {
    val importGroups = mutable.ListBuffer(mutable.ListBuffer.empty[Import])
    val nonImport = mutable.ListBuffer[Tree]()

    @tailrec
    def groupImports(imports: List[Tree]): Unit =
      imports match {
        case Nil =>
        case (i: Import) :: tail =>
          importGroups.head += i
          groupImports(tail)
        case tree :: tail =>
          if (importGroups.head.nonEmpty) {
            importGroups += mutable.ListBuffer.empty[Import]
          }

          nonImport += tree
          groupImports(tail)
      }

    groupImports(trees)
    (importGroups.map(_.toList).toList, nonImport.toList)
  }

  private def foldImports(imports: List[Import]): Unit =
    if (imports.size > 1) {
      val firstImportKeywordPos = imports.head.tokens.head.pos
      val lastImportPos = imports.last.pos

      val range =
        new FoldingRange(firstImportKeywordPos.endLine, lastImportPos.endLine)
      range.setStartCharacter(firstImportKeywordPos.endColumn)
      range.setEndCharacter(lastImportPos.endColumn)
      ranges.addAsIs(Imports, range)
    }

  private def span(pos: Position): Int = {
    if (pos == Position.None) Int.MaxValue
    else pos.endLine - pos.startLine
  }

  private def createRange(range: l.Range): FoldingRange = {
    val foldingRange =
      new FoldingRange(range.getStart.getLine, range.getEnd.getLine)
    foldingRange.setStartCharacter(range.getStart.getCharacter)
    foldingRange.setEndCharacter(range.getEnd.getCharacter)
    foldingRange
  }

  private def extractCommentRanges(
      tokens: Iterator[Token],
      ranges: FoldingRanges
  ): Unit = {

    @tailrec
    def findLastConsecutiveComment(acc: Token.Comment): Token.Comment =
      if (tokens.hasNext) {
        tokens.next() match {
          case token: Token.Comment => findLastConsecutiveComment(token)
          case _: Token.Space | _: Token.LF | _: Token.CR =>
            findLastConsecutiveComment(acc)
          case _ => acc
        }
      } else acc

    def isLineComment(comment: Token.Comment): Boolean =
      comment.toString().startsWith("//")

    while (tokens.hasNext) {
      tokens.next() match {
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

  private object Foldable {
    def unapply(tree: Tree): Option[Position] =
      tree match {
        case _: Term.Select => None
        case _: Term.Function => None
        case _: Pkg => None
        case _: Defn => None

        case _: Lit.String =>
          range(tree.pos.input, tree.pos.start + 3, tree.pos.end)

        case term: Term.Match =>
          for {
            token <- term.expr.findFirstTrailing(_.is[KwMatch])
            pos <- range(tree.pos.input, token.pos.end, term.pos.end)
          } yield pos

        case c: Case =>
          val isLastCase = c.parent.exists(_.children.lastOption.contains(c))
          val startingPoint = c.cond.getOrElse(c.pat)
          val bodyEnd = c.body.pos.end

          for {
            token <- startingPoint.findFirstTrailing(_.is[Token.RightArrow])
            end =
              if (isLastCase) bodyEnd + 1
              else bodyEnd // last case does not span until the closing bracket
            pos <- range(tree.pos.input, token.pos.end, end)
          } yield pos

        case term: Term.Try => // range for the `catch` clause
          for {
            startToken <- term.expr.findFirstTrailing(_.is[Token.KwCatch])
            lastCase <- term.catchp.lastOption
            endToken <- lastCase.findFirstTrailing(_.is[Token.RightBrace])
            tryPos =
              Position
                .Range(tree.pos.input, startToken.pos.end, endToken.pos.end)
          } yield tryPos

        case For(endPosition) =>
          val start = tree.pos.start + 3
          val end = endPosition.start
          range(tree.pos.input, start, end)

        case template: Template =>
          template.inits.lastOption match {
            case Some(init) =>
              range(tree.pos.input, init.pos.end, template.pos.end)
            case None =>
              range(tree.pos.input, template.pos.start, template.pos.end)
          }

        case _: Term.Block => Some(tree.pos)
        case _: Stat =>
          tree.parent.collect {
            case _: Term.Block | _: Term.Function | _: Template | _: Defn =>
              tree.pos
          }
        case _ => None
      }

    private def range(input: Input, start: Int, end: Int): Option[Position] =
      Some(Position.Range(input, start, end))

    private object For {
      def unapply(tree: Tree): Option[Position] =
        tree match {
          case loop: Term.For => Some(loop.body.pos)
          case loop: Term.ForYield =>
            for {
              token <- loop.body.findFirstLeading(_.is[Token.KwYield])
            } yield token.pos
          case _ => None
        }
    }
  }
}
