package scala.meta.internal.pc

import java.nio.file.Paths
import java.{util => ju}

import scala.collection.JavaConverters._

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import org.eclipse.lsp4j.SelectionRange

/**
 * Provides the functionality necessary for the `textDocument/selectionRange` request.
 *
 * @param compiler Metals Global presentation compiler wrapper.
 * @param params offset params converted from the selectionRange params.
 */
class SelectionRangeProvider(
    driver: MetalsDriver,
    params: ju.List[OffsetParams]
) {

  /**
   * Get the seletion ranges for the provider params
   *
   * @return selection ranges
   */
  def selectionRange(): List[SelectionRange] = {
    val selectionRanges = params.asScala.toList.map { param =>

      val uri = param.uri
      val filePath = Paths.get(uri)
      val source = SourceFile.virtual(filePath.toString, param.text)
      val result = driver.run(uri, param.text)
      given Context = result.context
      val path = result.pathTo(param.offset)

      val bareRanges = path
        .map { tree =>
          val selectionRange = new SelectionRange()
          selectionRange.setRange(tree.sourcePos.toLSP)
          selectionRange
        }

      bareRanges.reduceRight(setParent)
    }

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
    //Apply(
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
    //)
    if (child.getRange() == parent.getRange()) {
      parent
    } else {
      child.setParent(parent)
      child
    }
  }

}
