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
      val commentRanges = getCommentRanges(pos, path, param.text()).map { x =>
        new SelectionRange():
          setRange(x)
      }.toList
      // if cursor is in comment return range in comment

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
  implicit val sca3toplevel: Dialect =
    dialects.Scala3.withAllowToplevelTerms(true)

  def sourcePos2SelectionRange(pos: SourcePosition): SelectionRange =
    new SelectionRange():
      setRange(
        pos.toLsp
      )

  def commentRangesFromTokens(
      tokenList: List[Token],
      cursorStart: SourcePosition,
      offsetStart: Int,
  ) =
    val cursorStartShifted = cursorStart.start - offsetStart
    // lspStartOffset.
    val commentList: List[(Int, Int, Position)] = tokenList.collect {
      case x: Comment =>
        println(
          s"find Comment \"${x}\" at ${(x.start, x.`end`)} " // :
        ) // , ${(x.start, x.`end`, x.pos)}
        (x.start, x.`end`, x.pos)
    }

    // WIP: when there are multiple single line comments,select them together
    val merged = tokenList
      .foldLeft(List[List[Comment]](List())) { (acc, tok) =>
        val accRev = acc.reverse
        val accRevHd = accRev.head
        val accRevTl = accRev.tail
        tok match
          case x: Comment => (accRevHd :+ x) :: accRevTl
          case x: Trivia => accRevHd :: accRevTl
          case _ => acc :+ List()
      }
      .filter(_.nonEmpty)
      .reverse
      .filter(_.length >= 2)
      .map(x => (x.head.start, x.last.`end`, x.head.pos))

    println("merged: " + merged.map(x => (x._1, x._2)))

    val commentWithin =
      (commentList) // ++ merged)
        .filter((commentStart, commentEnd, _) =>
          commentStart <= cursorStartShifted && cursorStartShifted <= commentEnd
        )
        .map { (commentStart, commentEnd, oldPos) =>
          val oldLsp = oldPos.toLsp

          // val (s, e) = (oldLsp.getStart(), oldLsp.getEnd())
          // val newS = lsp4j.Position(
          //   s.getLine() + lspStartOffset.getLine(),
          //   s.getCharacter() + lspStartOffset.getCharacter(),
          // )

          // val newE = lsp4j.Position(
          //   e.getLine() + lspStartOffset.getLine(),
          //   e.getCharacter() + lspStartOffset.getCharacter(),
          // )

          // TODO: need to add offset to Position!
          val newPos: Position =
            meta.Position.Range(
              oldPos.input, // TODO: need to add offset to this!
              commentStart + offsetStart,
              commentEnd + offsetStart,
            )
          newPos.toLsp // convert
          // lsp4j.Range(newS, newE)
        }
    commentWithin
  end commentRangesFromTokens

  /** check pos.start,if it is within comment then expand to comment */
  def getCommentRanges(
      cursor: SourcePosition,
      path: List[tpd.Tree],
      srcText: String,
  )(using Context) =
    val (treeStart, treeEnd) = path.headOption
      .map(t => (t.sourcePos.start, t.sourcePos.`end`))
      .getOrElse((0, srcText.size))
    val lspStartOffset = treeStart

    // only parse comments from first range to reduce computation
    val srcSliced = srcText // .slice(treeStart, treeEnd)

    val tokens = srcSliced.tokenize.toOption
    if tokens.isEmpty then pprint.log("fail to parse in getCommentRanges!")

    commentRangesFromTokens(
      tokens.toList.flatten,
      cursor,
      lspStartOffset,
    )
  end getCommentRanges
end SelectionRangeProvider
