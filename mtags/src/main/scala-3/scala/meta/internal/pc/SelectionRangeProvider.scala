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
      val commentRanges = checkCmt(
        param,
        pos.start,
        pos.`end`,
      ).map { x =>
        val lspRange = new SelectionRange():
          setRange(
            x._3.toLsp
          )

        lspRange
      }.toList // if in comment return range in cmt

      (commentRanges ++ bareRanges).reduceRight(setParent)
    }
    // println(s"all selectionRanges ${selectionRanges}")

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

  /** check pos.start,if it is within comment then expand to comment */
  def checkCmt(param: OffsetParams, start: Int, end: Int) =
    // https://scalameta.org/docs/trees/guide.html
    val programStr = param.text()
    // deal with multiple source text
    val tkSrc = programStr.parse[Source].toOption.map(_.tokens)
    val tkExpr = programStr.parse[Stat].toOption.map(_.tokens)
    val tkType = programStr.parse[Type].toOption.map(_.tokens)

    val tokens = tkSrc orElse tkExpr orElse tkType
    val commentList = tokens.toList.flatten
      .collect { case x: Comment =>
        // println(s"find Comment : ${x}")
        x
      }
      .map(x => (x.start, x.`end`, x.pos))
    val commentWithin =
      commentList.filter((commentStart, commentEnd, _) =>
        commentStart <= start && start <= commentEnd
      )
    commentWithin
  end checkCmt
end SelectionRangeProvider
