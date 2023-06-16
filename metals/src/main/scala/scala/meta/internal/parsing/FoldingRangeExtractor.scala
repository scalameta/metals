package scala.meta.internal.parsing

import java.util

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

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
    // All Defn statements except one-liners must fold
    if (span(tree.pos) > spanThreshold) {
      val newEnclosing = tree match {
        case Foldable((pos, adjust))
            if tree.is[Defn] || span(enclosing) - span(
              pos
            ) > distanceToEnclosingThreshold =>
          distance.toRevised(pos.toLsp) match {
            case Some(revisedPos) =>
              if (pos.startLine != enclosing.startLine) {
                val range = createRange(revisedPos)
                ranges.add(Region, range, adjust)
              }
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
        tree: Tree
    ): Option[(Position, Boolean)] = {

      tree match {
        case _: Term.Select => None
        case _: Term.Function => None
        case _: Pkg => None
        case defn: Defn => getFoldingRangeForDefn(defn)

        case _: Lit.String =>
          Some(range(tree.pos.input, tree.pos.start + 3, tree.pos.end), true)

        case term: Term.Match =>
          for {
            (foldStart, adjust) <- findTermFoldStartAndAdjustment[
              Token.KwMatch
            ](
              term.expr.trailingTokens,
              startFoldIfEOL = true,
            )
            pos = range(tree.pos.input, foldStart, term.pos.end)
          } yield (pos, adjust)

        case c: Case =>
          val startingPoint = c.cond.getOrElse(c.pat)
          for {
            (foldStart, adjust) <- findTermFoldStartAndAdjustment[
              Token.RightArrow
            ](
              startingPoint.trailingTokens,
              startFoldIfEOL = false,
            )
            pos = range(tree.pos.input, foldStart, c.body.pos.end)
          } yield (pos, adjust)

        case term: Term.Try => // range for the `catch` clause
          for {
            (foldStart, adjust) <- findTermFoldStartAndAdjustment[
              Token.KwCatch
            ](
              term.expr.trailingTokens,
              startFoldIfEOL = false,
            )
            lastCase <- term.catchp.lastOption
            foldEnd =
              if (adjust)
                lastCase
                  .findFirstTrailing(_.is[Token.RightBrace])
                  .map(_.pos.end)
                  .getOrElse(lastCase.pos.end)
              else lastCase.pos.end
            pos = range(tree.pos.input, foldStart, foldEnd)
          } yield (pos, adjust)

        case For(endPosition) =>
          for {
            (foldStart, _) <- findTermFoldStartAndAdjustment[
              Token.KwFor
            ](
              tree.tokens.iterator,
              startFoldIfEOL = false,
            )
            pos = range(tree.pos.input, foldStart, endPosition.start)
          } yield (pos, true)

        case block: Term.Block =>
          val adjust =
            !isScala3BlockWithoutOptionalBraces(
              block
            ) && block.pos.startLine > 0

          val defaultBlockFoldRange = range(
            tree.pos.input,
            tree.pos.start,
            tree.pos.end,
          )

          block.parent match {
            case Some(_: Defn) | Some(_: Case) => None
            case Some(_: Term.ForYield) if !adjust =>
              Some(
                getFoldingRangeForBlockAfterKeyword[Token.KwYield](block)
                  .getOrElse(defaultBlockFoldRange),
                false,
              )
            case Some(_: Term.For) if !adjust =>
              Some(
                getFoldingRangeForBlockAfterKeyword[Token.KwDo](block)
                  .getOrElse(defaultBlockFoldRange),
                false,
              )
            case Some(_: Term.Try) if !adjust =>
              Some(
                getFoldingRangeForBlockAfterKeyword[Token.KwTry](block)
                  .getOrElse(defaultBlockFoldRange),
                false,
              )
            case _ =>
              Some(
                defaultBlockFoldRange,
                adjust,
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

    private def isScala3BlockWithoutOptionalBraces(tree: Tree): Boolean = {
      tree.children
        .find(child => child.isNot[Self] && child.isNot[Init])
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
        .getOrElse(true)
    }

    private def getFoldingRangeForDefn(
        defn: Tree
    ): Option[(Position, Boolean)] = {
      defn.children.lastOption.flatMap { child =>
        val isBraceless = isScala3BlockWithoutOptionalBraces(child)
        child match {
          case template: Template =>
            val foldStart = template.inits.lastOption
              .map(_.pos.end)
              .getOrElse(template.pos.start)

            Some(range(defn.pos.input, foldStart, defn.pos.end), !isBraceless)
          case block =>
            val defnTokenSlice = defn.tokens
              .takeWhile(_.pos.start < block.pos.start)

            val defnEqualsPosStart = defnTokenSlice
              .findLast(_.is[Token.Equals])
              .map(_.pos.start)

            if (defnEqualsPosStart.isDefined) {
              val tokensStartingwithEquals = defn.tokens
                .dropWhile(_.pos.start < defnEqualsPosStart.get)

              val startPosAndAdjust =
                findTermFoldStartAndAdjustment[Token.Equals](
                  tokensStartingwithEquals.iterator,
                  startFoldIfEOL = true,
                )

              startPosAndAdjust.map { case (startPos, adjust) =>
                (range(defn.pos.input, startPos, defn.pos.end), adjust)
              }
            } else {
              defnTokenSlice
                .findLast(t =>
                  t.isNot[Token.Whitespace] && t.isNot[Token.Comment]
                )
                .map(lastToken =>
                  (
                    range(defn.pos.input, lastToken.end, defn.pos.end),
                    !isBraceless,
                  )
                )
            }
        }
      }
    }

    private def findTermFoldStartAndAdjustment[T: ClassTag](
        trailingTokens: Iterator[Token],
        startFoldIfEOL: Boolean,
    ): Option[(Int, Boolean)] = {
      val firstTwoTokens = trailingTokens
        .withFilter(t =>
          (startFoldIfEOL && t.is[Token.EOL]) || t.isNot[Token.Whitespace] && t
            .isNot[Token.Comment]
        )
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
          // braceless or brace starts on another line (if reactToNewLine is set)
          Some(
            kw.pos.end,
            /* adjust */ false,
          )
        case _ => None
      }
    }

    private def getFoldingRangeForBlockAfterKeyword[T: ClassTag](
        block: Term.Block
    ): Option[Position] = {
      val firstToken = block.leadingTokens
        .filter(t => t.isNot[Token.Whitespace] && t.isNot[Token.Comment])
        .headOption
      firstToken match {
        case Some(kw: T) =>
          Some(
            range(
              block.pos.input,
              kw.pos.end,
              block.pos.end,
            )
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
