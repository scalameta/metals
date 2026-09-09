package scala.meta.internal.metals

import java.{util => ju}

import scala.meta.internal.metals.AdjustedLspData.LineColumn
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.{semanticdb => s}
import scala.meta.pc
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.HoverSignature

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.{Range => LspRange}

trait AdjustLspData {

  def adjust(pos: LineColumn): LineColumn

  def adjustPosition(
      position: Position,
      adjustToZero: Boolean = true,
  ): Position = {
    val lineColumn = (position.getLine(), position.getCharacter())
    val (adjustedLine, adjustedCharacter) = adjust(lineColumn)
    val finalAdjustedCharacter =
      if (adjustToZero && adjustedCharacter < 0) 0 else adjustedCharacter
    val finalAdjustedLine =
      if (adjustToZero && adjustedLine < 0) 0 else adjustedLine
    new Position(finalAdjustedLine, finalAdjustedCharacter)
  }

  def adjustTextDocument(
      document: s.TextDocument,
      originalText: String,
  ): s.TextDocument = {

    def adjustSemanticdbRange(range: s.Range): s.Range = {
      val (adjustedStartLine, adjustedStartCharacter) = adjust(
        (range.startLine, range.startCharacter)
      )
      val (adjustedEndLine, adjustedEndCharacter) = adjust(
        (range.endLine, range.endCharacter)
      )
      new s.Range(
        adjustedStartLine,
        adjustedStartCharacter,
        adjustedEndLine.toShort,
        adjustedEndCharacter.toShort,
      )
    }
    val adjustedOccurences =
      document.occurrences.flatMap { occurence =>
        occurence.range
          .map(r => occurence.copy(range = Some(adjustSemanticdbRange(r))))
      }

    val adjustedDiagnostic =
      document.diagnostics.flatMap { diagnostic =>
        diagnostic.range
          .map(r => diagnostic.copy(range = Some(adjustSemanticdbRange(r))))
      }

    val adjustedSynthetic =
      document.synthetics.flatMap { synthetic =>
        synthetic.range
          .map(r => synthetic.copy(range = Some(adjustSemanticdbRange(r))))
      }

    s.TextDocument(
      schema = document.schema,
      uri = document.uri,
      text = originalText,
      md5 = MD5.compute(originalText),
      language = document.language,
      symbols = document.symbols,
      occurrences = adjustedOccurences,
      diagnostics = adjustedDiagnostic,
      synthetics = adjustedSynthetic,
    )
  }

  def adjustRange(range: LspRange): LspRange =
    new LspRange(
      adjustPosition(range.getStart),
      adjustPosition(range.getEnd),
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

  def adjustLocation(location: Location): Location =
    new Location(location.getUri(), adjustRange(location.getRange()))

  def adjustReferencesResult(
      referencesResult: pc.ReferencesResult,
      additionalAdjust: AdjustRange,
      text: String,
  ): ReferencesResult =
    new ReferencesResult(
      referencesResult.symbol,
      referencesResult
        .locations()
        .asScala
        .flatMap(loc =>
          additionalAdjust(loc, text, referencesResult.symbol)
            .map(adjustLocation)
        )
        .toList,
    )

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

  def adjustHoverResp(hover: HoverSignature): HoverSignature =
    hover.getRange().map(rng => hover.withRange(adjustRange(rng))).orElse(hover)

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
    adjustLineColumn: LineColumn => LineColumn,
    filterOutLocations: Location => Boolean,
    adjustUri: String => String = identity,
) extends AdjustLspData {

  override def adjustLocations(
      locations: ju.List[Location]
  ): ju.List[Location] = {
    locations.asScala.collect {
      case loc if !filterOutLocations(loc) =>
        loc.setRange(adjustRange(loc.getRange()))
        loc.setUri(adjustUri(loc.getUri()))
        loc
    }.asJava
  }
  override def adjust(
      pos: LineColumn
  ): LineColumn = adjustLineColumn(pos)

}

object DefaultAdjustedData extends AdjustLspData {

  override def adjust(
      pos: LineColumn
  ): LineColumn = identity(pos)

  override def adjustRange(range: LspRange): LspRange = identity(range)

  override def adjustTextDocument(
      document: s.TextDocument,
      originalText: String,
  ): s.TextDocument = identity(document)

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
      f: LineColumn => LineColumn,
      filterOutLocations: Location => Boolean = _ => false,
      adjustUri: String => String = identity,
  ): AdjustLspData =
    AdjustedLspData(
      f,
      filterOutLocations,
      adjustUri,
    )

  val default: AdjustLspData = DefaultAdjustedData

  type LineColumn = (Int, Int)
}
