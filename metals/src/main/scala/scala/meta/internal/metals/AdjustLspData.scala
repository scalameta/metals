package scala.meta.internal.metals

import java.{util => ju}

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.AutoImportsResult

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
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
      adjustPos(range.getEnd),
    )

  def adjustTextEdits(
      edits: ju.List[TextEdit]
  ): java.util.List[TextEdit] = {
    edits.asScala.map { loc =>
      loc.setRange(adjustRange(loc.getRange()))
      loc
    }.asJava
  }

  def adjustDocumentHighlight(
      highlights: ju.List[DocumentHighlight]
  ): java.util.List[DocumentHighlight] = {
    highlights.asScala.map { loc =>
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
  ): ju.List[Location]

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
      for (textEdit <- item.getLeftTextEdit())
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

case class AdjustedLspData(
    adjustPosition: Position => Position,
    filterOutLocations: Location => Boolean,
) extends AdjustLspData {

  override def adjustLocations(
      locations: ju.List[Location]
  ): ju.List[Location] = {
    locations.asScala.collect {
      case loc if !filterOutLocations(loc) =>
        loc.setRange(adjustRange(loc.getRange()))
        loc
    }.asJava
  }
  override def adjustPos(pos: Position): Position = adjustPosition(pos)

}

object DefaultAdjustedData extends AdjustLspData {

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

object AdjustedLspData {

  def create(
      f: Position => Position,
      filterOutLocations: Location => Boolean = _ => false,
  ): AdjustLspData =
    AdjustedLspData(
      pos => {
        val newPos = f(pos)
        if (newPos.getLine() < 0)
          pos
        else newPos
      },
      filterOutLocations,
    )

  val default: AdjustLspData = DefaultAdjustedData
}
