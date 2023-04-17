package scala.meta.internal.pc

import java.nio.file.Paths
import java.{util as ju}

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import org.eclipse.lsp4j.SelectionRange

import SelectionRangeProvider.*
import dotty.tools.dotc.semanticdb.Scala3

import scala.meta.tokens.Token
import dotty.tools.dotc.util.SourcePosition
import scala.meta.inputs.Position
import scala.meta.tokens.Token.Trivia
import org.eclipse.lsp4j

/**
 * Provides the functionality necessary for the `textDocument/selectionRange` request.
 *
 * @param compiler Metals Global presentation compiler wrapper.
 * @param params offset params converted from the selectionRange params.
 */
class SelectionRangeProvider(
    driver: InteractiveDriver,
    params: ju.List[OffsetParams],
):

  /**
   * Get the seletion ranges for the provider params
   *
   * @return selection ranges
   */
  def selectionRange(): List[SelectionRange] =
    given ctx: Context = driver.currentCtx

    val selectionRanges = params.asScala.toList.map { param =>

      val uri = param.uri
      val filePath = Paths.get(uri)
      val source = SourceFile.virtual(filePath.toString, param.text)
      driver.run(uri, source)
      val pos = driver.sourcePosition(param)
      val path =
        Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)

      val bareRanges = path
        .map { tree =>
          val selectionRange = new SelectionRange()
          selectionRange.setRange(tree.sourcePos.toLsp)
          selectionRange
        }

      // if cursor is in comment return range in comment4
      val commentRanges = getCommentRanges(pos, path, param.text()).map { x =>
        new SelectionRange():
          setRange(x)
      }.toList

      (commentRanges ++ bareRanges)
        .reduceRightOption(setParent)
        .getOrElse(new SelectionRange())
    }

    selectionRanges
  end selectionRange

  private def setParent(
      child: SelectionRange,
      parent: SelectionRange,
  ): SelectionRange =
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
    if child.getRange() == parent.getRange() then parent
    else
      child.setParent(parent)
      child

end SelectionRangeProvider

object SelectionRangeProvider:
  import scala.meta.*
  import scala.meta.Token.Comment
  import dotty.tools.dotc.ast.tpd

  // overwrites implicit 'implicit def current: Scala213' in scalameta
  implicit val sca3AllowToplevel: Dialect =
    dialects.Scala3.withAllowToplevelTerms(true)

  def commentRangesFromTokens(
      tokenList: List[Token],
      cursorStart: SourcePosition,
      offsetStart: Int,
  ) =
    val cursorStartShifted = cursorStart.start - offsetStart

    val commentWithin =
      tokenList
        .collect { case x: Comment =>
          // println(s"find Comment \"${x}\" at ${(x.start, x.`end`)} ")
          (x.start, x.`end`, x.pos)
        }
        .filter((commentStart, commentEnd, _) =>
          commentStart <= cursorStartShifted && cursorStartShifted <= commentEnd
        )
        .map { (commentStart, commentEnd, oldPos) =>
          val oldLsp = oldPos.toLsp

          // TODO: need to add offset to Position in oldPos.input for more efficient impl
          val newPos: Position =
            meta.Position.Range(
              oldPos.input,
              commentStart + offsetStart,
              commentEnd + offsetStart,
            )
          newPos.toLsp
        }
    commentWithin
  end commentRangesFromTokens

  /** get comments under cursor */
  def getCommentRanges(
      cursor: SourcePosition,
      path: List[tpd.Tree],
      srcText: String,
  )(using Context) =
    val (treeStart, treeEnd) = path.headOption
      .map(t => (t.sourcePos.start, t.sourcePos.`end`))
      .getOrElse((0, srcText.size))
    val startOffset = 0 // should be set to treeStart, but currently set to 0

    // only parse comments from first range to reduce computation
    val srcSliced = srcText // .slice(treeStart, treeEnd)

    val tokens = srcSliced.tokenize.toOption
    if tokens.isEmpty then pprint.log("fail to parse in getCommentRanges!")

    commentRangesFromTokens(
      tokens.toList.flatten,
      cursor,
      startOffset,
    )
  end getCommentRanges
end SelectionRangeProvider
