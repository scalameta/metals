package scala.meta.internal.metals.notebook

import java.{util => ju}

import scala.annotation.tailrec

import scala.meta.internal.metals.AdjustLspData
import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position

/**
 * Adjusts positions/locations produced by typechecking a notebook's
 * concatenated cells back to the requesting cell's own coordinates.
 * Hover/completion/diagnostic ranges are always local to the requesting
 * cell, so the single fixed `startLine` shift in [[adjustPos]] is correct
 * for them. `Location`-returning features (definition, references, ...) can
 * point anywhere in the concatenation, so [[adjustLocation]] finds that
 * location's own owning cell from `offsets` and re-targets it there instead.
 */
final class NotebookAdjustLspData(
    cellUris: Vector[String],
    offsets: Vector[Int],
    startLine: Int,
) extends AdjustLspData {

  override def adjustPos(
      pos: Position,
      adjustToZero: Boolean = true,
  ): Position = {
    val adjusted = new Position(pos.getLine - startLine, pos.getCharacter)
    if (adjustToZero) {
      if (adjusted.getCharacter < 0) adjusted.setCharacter(0)
      if (adjusted.getLine < 0) adjusted.setLine(0)
    }
    adjusted
  }

  override def adjustLocation(location: Location): Location = {
    val idx = ownerIndex(location.getRange.getStart.getLine)
    val cellOffset = offsets(idx)

    def shift(pos: Position): Unit =
      pos.setLine(math.max(0, pos.getLine - cellOffset))

    shift(location.getRange.getStart)
    shift(location.getRange.getEnd)
    location.setUri(cellUris(idx))
    location
  }

  override def adjustLocations(
      locations: ju.List[Location]
  ): ju.List[Location] =
    locations.map(adjustLocation)

  @tailrec
  private def ownerIndex(line: Int, idx: Int = 0): Int =
    if (idx + 1 < offsets.length && offsets(idx + 1) <= line)
      ownerIndex(line, idx + 1)
    else idx
}
