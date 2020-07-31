package scala.meta.internal.metals

import java.{util => ju}

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.AutoImportsResult

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.{Range => LspRange}

trait AdjustLspData {

  def adjustPos(pos: Position): Position

  def adjustRange(range: LspRange): LspRange =
    new LspRange(
      adjustPos(range.getStart),
      adjustPos(range.getEnd)
    )

  def adjustTextEdits(
      edits: ju.List[TextEdit]
  ): java.util.List[TextEdit] = {
    edits.asScala.map { loc =>
      loc.setRange(adjustRange(loc.getRange()))
      loc
    }.asJava
  }

  def adjustDiagnostic(
      diag: Diagnostic
  ): Diagnostic = {
    diag.setRange(adjustRange(diag.getRange()))
    diag
  }

  def adjustLocations(
      locations: java.util.List[Location]
  ): ju.List[Location] = {
    locations.asScala.map { loc =>
      loc.setRange(adjustRange(loc.getRange()))
      loc
    }.asJava
  }

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

object DefaultAdjust extends AdjustLspData {

  override def adjustPos(pos: Position): Position = identity(pos)

  override def adjustRange(range: LspRange): LspRange = identity(range)

  override def adjustTextEdits(
      edits: java.util.List[TextEdit]
  ): java.util.List[TextEdit] = identity(edits)

  override def adjustLocations(
      locations: java.util.List[Location]
  ): java.util.List[Location] = identity(locations)

  override def adjustHoverResp(hover: Hover): Hover = identity(hover)

  override def adjustCompletionListInPlace(list: CompletionList): Unit = {}

  override def adjustImportResult(
      autoImportResult: AutoImportsResult
  ): Unit = {}

  override def adjustDiagnostic(
      diag: Diagnostic
  ): Diagnostic = identity(diag)
}

object AdjustLspData {

  def create(f: Position => Position): AdjustLspData =
    new AdjustLspData {
      override def adjustPos(pos: Position): Position = {
        val newPos = f(pos)
        if (newPos.getLine() < 0)
          pos
        else newPos
      }
    }

  val default: AdjustLspData = DefaultAdjust
}
