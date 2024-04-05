package scala.meta.internal.pc

import java.{util => ju}

import scala.meta._
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.OffsetParams
import scala.meta.tokens.Token
import scala.meta.tokens.Token.Comment

import org.eclipse.lsp4j.SelectionRange

/**
 * Provides the functionality necessary for the `textDocument/selectionRange` request.
 *
 * @param compiler Metals Global presentation compiler wrapper.
 * @param params offset params converted from the selectionRange params.
 */
class SelectionRangeProvider(
    val compiler: MetalsGlobal,
    params: ju.List[OffsetParams]
) {

  /**
   * Get the seletion ranges for the provider params
   *
   * @return selection ranges
   */
  def selectionRange(): List[SelectionRange] = {
    import compiler._

    val selectionRanges = params.asScala.toList.map { param =>
      val unit = addCompilationUnit(
        code = param.text(),
        filename = param.uri().toString(),
        cursor = None
      )

      val pos = unit.position(param.offset)

      // NOTE that we locate the pos in the tree _only_ so that we have a fresh
      // lastVisitedParentTrees that will contain the exact tree structure we
      // need to create the selection range, starting from the position
      val _ = locateUntyped(pos)
      val bareRanges = lastVisitedParentTrees
        .map { tree: Tree =>
          val selectionRange = new SelectionRange()
          selectionRange.setRange(tree.pos.toLsp)
          selectionRange
        }

      val commentRanges =
        getCommentRanges(pos, lastVisitedParentTrees, param.text()).map { x =>
          new SelectionRange() { setRange(x.toLsp) }
        }.toList
      // (commentRanges ++ bareRanges).reduceRight(setParent)
      (commentRanges ++ bareRanges)
        .reduceRightOption(setParent)
        .getOrElse(new SelectionRange())
    }
    println("selectionRanges scala 2")
    println(selectionRanges map (_.getRange().toString()))
    selectionRanges
  }

  private def setParent(
      child: SelectionRange,
      parent: SelectionRange
  ): SelectionRange = {
    // If the parent and the child have the same exact range we just skip it.
    // This happens in a lot of various places. For example:
    //
    // val total = for {
    //   a <- >>region>>Some(1)<<region<<
    // } yield a
    //
    // Apply(
    //  Select(Apply(Ident(Some), List(Literal(Constant(1)))), flatMap), <-- This range
    //  List(
    //    Function(
    //      List(ValDef(Modifiers(8192L, , List()), a, <type ?>, <empty>)),
    //      Apply(
    //        Select(Apply(Ident(Some), List(Literal(Constant(2)))), map), <-- Same as this range
    //        ...
    //      )
    //    )
    //  )
    // )
    if (child.getRange() == parent.getRange()) {
      parent
    } else {
      child.setParent(parent)
      child
    }
  }

  import compiler._
  def getCommentRanges(
      cursorPos: Position,
      path: List[Tree],
      srcText: String
  ): List[Position] = {

    val (treeStart, treeEnd) = path.headOption
      .map(t => (t.pos.start, t.pos.end))
      .getOrElse((0, srcText.size))

    // only parse comments from first range to reduce computation
    val srcSliced = srcText.slice(treeStart, treeEnd)

    val tokens = srcSliced.tokenize.toOption
    val rg =
      if (tokens.isEmpty) Nil
      else
        SelectionRangeUtils
          .commentRangesFromTokens(
            tokens.toList.flatten,
            cursorPos.start,
            treeStart
          ) map { case (s, e) =>
          cursorPos
            .withStart(s)
            .withEnd(e)
        }

    println(
      "comment range scala 2: " + rg
        .map(x => srcText.slice(x.start, x.end))
        .mkString(",")
    )
    rg
  }

  def commentRangesFromTokens(
      tokenList: List[Token],
      cursorStart: Position,
      offsetStart: Int
  ): List[Position] = {
    val cursorStartShifted = cursorStart.start - offsetStart

    tokenList
      .collect { case x: Comment =>
        (x.start, x.end, x.pos)
      }
      .collect {
        case (commentStart, commentEnd, _)
            if commentStart <= cursorStartShifted && cursorStartShifted <= commentEnd =>
          cursorStart
            .withStart(commentStart + offsetStart)
            .withEnd(commentEnd + offsetStart)

      }
  }

}
