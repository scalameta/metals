package scala.meta.internal.parsing

import java.util
import scala.annotation.tailrec
import scala.collection.mutable
import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.tokens.Token
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind._
import org.eclipse.{lsp4j => l}

import scala.reflect.ClassTag

final class FoldingRangeExtractor(
    distance: TokenEditDistance,
    foldOnlyLines: Boolean,
) {
  private val spanThreshold = 2

  private val ranges = new FoldingRanges(foldOnlyLines)

  def extract(tree: Tree): util.List[FoldingRange] = {
    extractCommentRanges(tree.tokens.iterator, ranges)
    extractFrom(tree, Position.None)
    ranges.get
  }

  def extractFrom(tree: Tree, enclosing: Position): Unit = {
    val newEnclosing = tree match {
      case Foldable((pos, adjust)) =>
        distance.toRevised(pos.toLsp) match {
          case Some(revisedPos) =>
            val range = createRange(revisedPos)
            ranges.add(Region, range, adjust)
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
      ranges: FoldingRanges,
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
    def unapply(
        tree: Tree
    ): Option[(Position, Boolean)] = {

      // All Defn statements must fold
      if (
        (tree
          .isNot[Defn] || span(tree.pos) == 0) && span(tree.pos) < spanThreshold
      )
        return None

      // Case and Defn statement take care of their own folding no need for direct children to be evaluated
      if (tree.parent.exists(p => p.is[Case] || p.is[Defn]))
        return None

      tree match {
        case _: Term.Select => None
        case _: Term.Function => None
        case _: Pkg => None
        case defn: Defn =>
          for {
            foldStart <- findDefnFoldStart(defn)
            lastChild <- defn.children.lastOption
            adjust = !isScala3BlockWithoutOptionalBraces(lastChild)
          } yield (
            range(
              tree.pos.input,
              foldStart,
              tree.pos.end,
            ),
            adjust,
          )

        case _: Lit.String =>
          Some(range(tree.pos.input, tree.pos.start + 3, tree.pos.end), true)

        case term: Term.Match =>
          matchBlockStartAndFoldingAdjustment[Token.KwMatch](
            term.expr.trailingTokens
          ) match {
            case Some(foldStartIndexAndAdjust) =>
              Some(
                range(
                  tree.pos.input,
                  foldStartIndexAndAdjust._1,
                  term.pos.end,
                ),
                foldStartIndexAndAdjust._2,
              )
            case None => None
          }

        case c: Case =>
          val startingPoint = c.cond.getOrElse(c.pat)
          for {
            (foldStart, adjust) <- matchBlockStartAndFoldingAdjustment[
              Token.RightArrow
            ](
              startingPoint.trailingTokens
            )
            pos = range(tree.pos.input, foldStart, c.body.pos.end)
          } yield (pos, adjust)

        case term: Term.Try => // range for the `catch` clause
          matchBlockStartAndFoldingAdjustment[Token.KwCatch](
            term.expr.trailingTokens
          ) match {
            case Some(foldStartIndexAndAdjust) =>
              Some(
                range(
                  tree.pos.input,
                  foldStartIndexAndAdjust._1,
                  term.pos.end,
                ),
                foldStartIndexAndAdjust._2,
              )
            case None => None
          }

        case For(endPosition) =>
          matchBlockStartAndFoldingAdjustment[Token.KwFor](
            tree.tokens.iterator
          ) match {
            case Some(foldStartIndexAndAdjust) =>
              Some(
                range(
                  tree.pos.input,
                  foldStartIndexAndAdjust._1,
                  endPosition.start,
                ),
                true,
              )
            case None => None
          }

        case template: Template =>
          // TODO is this case needed now if we handle folding from defn case?
          val adjust = !isScala3BlockWithoutOptionalBraces(template)
          template.inits.lastOption match {
            case Some(init) =>
              Some(
                range(
                  tree.pos.input,
                  init.pos.end,
                  template.pos.end,
                ),
                adjust,
              )
            case None =>
              Some(
                range(
                  tree.pos.input,
                  template.pos.start,
                  template.pos.end,
                ),
                adjust,
              )
          }

        case block: Term.Block => {
          val adjust =
            !isScala3BlockWithoutOptionalBraces(
              block
            ) && block.pos.startLine > 0

          Some(
            range(
              tree.pos.input,
              tree.pos.start,
              tree.pos.end,
            ),
            adjust, // if braceless add as is, if not adjust folding range
          )
        }

        case _: Stat =>
          tree.parent.collect {
            case _: Term.Block | _: Term.Function | _: Template | _: Defn =>
              (tree.pos, false)
          }
        case _ => None
      }
    }

    private def isScala3BlockWithoutOptionalBraces(tree: Tree) = {
      tree.children
        .find(_.isNot[Self])
        .map { child =>
          val childStartPos = child.pos.start
          val LeftBracesNotClosed = tree.tokens
            .takeWhile(_.pos.start < childStartPos)
            .filter(t => t.is[Token.LeftBrace] || t.is[Token.RightBrace])
            .foldLeft(0) {
              case (n, _: Token.LeftBrace) => n + 1
              case (n, _) => n - 1
            }
          LeftBracesNotClosed == 0
        }
        .getOrElse(false)
    }

    private def findDefnFoldStart(defn: Tree): Option[Int] = {
      defn.children.lastOption.map {
        case template: Template =>
          template.inits.lastOption
            .map(_.pos.end)
            .getOrElse(template.pos.start)
        case block =>
          val defnTokenSlice = defn.tokens
            .takeWhile(_.pos.start < block.pos.start)

          val endOfLastEquals = defnTokenSlice
            .findLast(_.is[Token.Equals])
            .map(_.pos.end)

          endOfLastEquals.getOrElse(
            defnTokenSlice
              .findLast(_.isNot[Token.Whitespace])
              .map(_.pos.end)
              .getOrElse(defnTokenSlice.last.pos.end)
          )
      }
    }

    private def matchBlockStartAndFoldingAdjustment[T: ClassTag](
        trailingTokens: Iterator[Token]
    ): Option[(Int, Boolean)] = {
      val firstTwoTokens = trailingTokens
        .withFilter(_.isNot[Token.Whitespace])
        .take(2)
        .toList
      firstTwoTokens match {
        case List(_: T, brace: Token.LeftBrace) =>
          // with braces
          Some(
            brace.pos.start,
            /* adjust */ true,
          )
        case (kw: T) :: _ =>
          // braceless
          Some(
            kw.pos.end,
            /* adjust */ false,
          )
        case _ => None
      }
    }

    private def range(input: Input, start: Int, end: Int): Position =
      Position.Range(input, start, end)

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
