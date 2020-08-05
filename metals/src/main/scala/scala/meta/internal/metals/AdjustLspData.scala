package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.AutoImportsResult

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.{Range => LspRange}

trait AdjustLspData {

  def adjustPos(pos: Position): Position

  def adjustRange(range: LspRange): LspRange =
    new LspRange(
      adjustPos(range.getStart),
      adjustPos(range.getEnd)
    )

  def adjustHoverResp(hover: Hover): Hover =
    if (hover.getRange == null)
      hover
    else {
      val newRange = adjustRange(hover.getRange)
      val newHover = new Hover
      newHover.setContents(hover.getContents)
      newHover.setRange(newRange)
      newHover
    }

  def adjustCompletionListInPlace(list: CompletionList): Unit = {
    for (item <- list.getItems.asScala) {
      for (textEdit <- Option(item.getTextEdit))
        textEdit.setRange(adjustRange(textEdit.getRange))
      for (l <- Option(item.getAdditionalTextEdits); textEdit <- l.asScala)
        textEdit.setRange(adjustRange(textEdit.getRange))
    }
  }

  def adjustImportResult(
      autoImportResult: AutoImportsResult
  ): Unit = {
    for (textEdit <- autoImportResult.edits.asScala) {
      textEdit.setRange(adjustRange(textEdit.getRange))
    }
  }
}

object AdjustLspData {

  def create(f: Position => Position): AdjustLspData =
    new AdjustLspData {
      override def adjustPos(pos: Position): Position = f(pos)
    }

  val default: AdjustLspData = AdjustLspData.create(identity)
}
