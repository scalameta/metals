package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

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
   * Get the selection ranges for the provider params
   *
   * @return selection ranges
   */
  def selectionRange(): List[l.SelectionRange] = {
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
        .flatMap {
          case tree: DefDef =>
            val paramsSelectionRange = (tree.tparams :: tree.vparamss)
              .filter { paramList =>
                paramList.exists(_.pos.encloses(pos)) && paramList.length >= 2
              }
              .map { paramList =>
                new l.SelectionRange() {
                  setRange(
                    new l.Range(
                      paramList.head.pos.toLsp.getStart,
                      paramList.last.pos.toLsp.getEnd
                    )
                  )
                }
              }
            val defSelectionRange = new l.SelectionRange() {
              setRange(tree.pos.toLsp)
            }
            paramsSelectionRange :+ defSelectionRange

          case tree =>
            val selectionRange = new l.SelectionRange() {
              setRange(tree.pos.toLsp)
            }
            List(selectionRange)
        }

      val commentRanges =
        getCommentRanges(pos, lastVisitedParentTrees, param.text()).map { x =>
          new l.SelectionRange() { setRange(x.toLsp) }
        }.toList

      (commentRanges ++ bareRanges)
        .reduceRightOption(setParent)
        .getOrElse(new l.SelectionRange())
    }

    selectionRanges
  }

  private def setParent(
      child: l.SelectionRange,
      parent: l.SelectionRange
  ): l.SelectionRange = {
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

    // only tokenize comments from first range to reduce computation
    val srcSliced = srcText.slice(treeStart, treeEnd)

    val tokens = srcSliced.safeTokenize.toOption

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

  }

}
