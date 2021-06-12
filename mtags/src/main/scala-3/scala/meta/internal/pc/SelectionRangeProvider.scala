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
    driver: InteractiveDriver,
    params: ju.List[OffsetParams]
) {

  /**
   * Get the seletion ranges for the provider params
   *
   * @return selection ranges
   */
  def selectionRange(): List[SelectionRange] = {
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
    child.setParent(parent)
    child
  }

}
