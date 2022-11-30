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

final class FoldingRangeExtractor(
    distance: TokenEditDistance,
    foldOnlyLines: Boolean,
) {
  private val spanThreshold = 2
  private val distanceToEnclosingThreshold = 3

  private val ranges = new FoldingRanges(foldOnlyLines)

  def extract(tree: Tree): util.List[FoldingRange] = {
    extractCommentRanges(tree.tokens.iterator, ranges)
    extractFrom(tree, Position.None)
    ranges.get
  }

  def extractFrom(tree: Tree, enclosing: Position): Unit = {
    if (span(tree.pos) > 1) {
      val newEnclosing = (tree, enclosing) match {
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
        treeAndEnclosing: (Tree, Position)
    ): Option[(Position, Boolean)] = {
      val tree = treeAndEnclosing._1
      val parentDefnStart = tree.parent.filter(_.is[Defn]).map(_.pos.start)

      tree match {
        case _: Term.Select => None
        case _: Term.Function => None
        case _: Pkg => None
        case _: Defn => None

        case _: Lit.String =>
          Some(range(tree.pos.input, tree.pos.start + 3, tree.pos.end), true)

        case term: Term.Match =>
          val firstTwoTokens = term.expr.trailingTokens
            .withFilter(_.isNot[Token.Whitespace])
            .take(2)
            .toList
          firstTwoTokens match {
            case List(_: Token.KwMatch, brace: Token.LeftBrace) =>
              // with braces
              Some(
                range(
                  tree.pos.input,
                  parentDefnStart.getOrElse(brace.pos.end),
                  term.pos.end,
                ), /* adjust */ parentDefnStart.isEmpty,
              )
            case (matchKw: Token.KwMatch) :: _ =>
              // braceless
              Some(
                range(
                  tree.pos.input,
                  parentDefnStart.getOrElse(matchKw.pos.end),
                  term.pos.end,
                ), /* adjust */ false,
              )
            case _ => None
          }

        case c: Case =>
          val startingPoint = c.cond.getOrElse(c.pat)
          val bodyEnd = {
            c.body.pos match {
              case Position.None => c.body.pos.end
              case Position.Range(input, _, end) =>
                val char = input.chars(end)
                if (char == '\r') end + 2
                else end + 1
            }
          }
          for {
            token <- startingPoint.findFirstTrailing(_.is[Token.RightArrow])
            pos = range(tree.pos.input, token.pos.end, bodyEnd)
          } yield (pos, true)

        case term: Term.Try => // range for the `catch` clause
          val firstTwoTokens = term.expr.trailingTokens
            .withFilter(_.isNot[Token.Whitespace])
            .take(2)
            .toList
          firstTwoTokens match {
            case List(_: Token.KwCatch, brace: Token.LeftBrace) =>
              // with braces
              Some(
                range(
                  tree.pos.input,
                  parentDefnStart.getOrElse(brace.pos.end),
                  term.pos.end,
                ), /* adjust */ parentDefnStart.isEmpty,
              )
            case (catchKw: Token.KwCatch) :: _ =>
              // braceless
              Some(
                range(
                  tree.pos.input,
                  parentDefnStart.getOrElse(catchKw.pos.end),
                  term.pos.end,
                ), /* adjust */ false,
              )
            case _ => None
          }

        case For(endPosition) =>
          val start = tree.pos.start + 3
          val end = endPosition.start
          Some(
            range(tree.pos.input, parentDefnStart.getOrElse(start), end),
            parentDefnStart.isEmpty,
          )

        case template: Template =>
          val adjust = !isScala3BlockWithoutOptionalBraces(
            template
          ) && parentDefnStart.isEmpty
          template.inits.lastOption match {
            case Some(init) =>
              Some(
                range(
                  tree.pos.input,
                  parentDefnStart.getOrElse(init.pos.end),
                  template.pos.end,
                ),
                adjust,
              )
            case None =>
              Some(
                range(
                  tree.pos.input,
                  parentDefnStart.getOrElse(template.pos.start),
                  template.pos.end,
                ),
                adjust,
              )
          }

        case block: Term.Block => {
          val braceless =
            isScala3BlockWithoutOptionalBraces(block) && block.pos.startLine > 0
          if (braceless) {
            Some(
              range(
                tree.pos.input,
                parentDefnStart.getOrElse(
                  tree.pos.input
                    .lineToOffset(tree.pos.startLine) - System
                    .lineSeparator()
                    .size
                ),
                tree.pos.end,
              ),
              false, // if braceless add as is
            )
          } else if (span(block.pos) - 1 > spanThreshold) {
            Some(
              range(
                tree.pos.input,
                parentDefnStart.getOrElse(tree.pos.start),
                tree.pos.end,
              ),
              parentDefnStart.isEmpty,
            )
          } else {
            None
          }
        }
        case _: Stat =>
          tree.parent.collect {
            case _: Term.Block | _: Term.Function | _: Template | _: Defn =>
              (tree.pos, true)
          }
        case _ => None
      }
    }

    private def isScala3BlockWithoutOptionalBraces(tree: Tree) = {
      val firstChildTokens =
        tree.children.headOption.map(_.tokens).getOrElse(Tokens(Array.empty))
      val blockTokens = tree.parent.get.tokens
      val LeftBracesNotClosed = blockTokens
        .slice(0, blockTokens.indexOfSlice(firstChildTokens))
        .filter(t => t.is[Token.LeftBrace] || t.is[Token.RightBrace])
        .foldLeft(0) {
          case (n, _: Token.LeftBrace) => n + 1
          case (n, _: Token.RightBrace) => n - 1
        }
      LeftBracesNotClosed == 0
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
