package tests

import java.util

import scala.meta.internal.jdk.CollectionConverters._

import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

object RangesTextEdits {
  def fromFoldingRanges(ranges: util.List[l.FoldingRange]): List[l.TextEdit] = {
    ranges.asScala.flatMap(convert).toList
  }

  def fromSelectionRanges(
      ranges: List[l.SelectionRange]
  ): List[l.TextEdit] = {
    ranges.flatMap(convert).toList
  }

  private def convert(range: l.FoldingRange): List[l.TextEdit] = {
    val start = new l.Position(range.getStartLine, range.getStartCharacter)
    val end = new l.Position(range.getEndLine, range.getEndCharacter)
    List(
      new TextEdit(new l.Range(start, start), s">>${range.getKind}>>"),
      new TextEdit(new l.Range(end, end), s"<<${range.getKind}<<")
    )
  }

  private def convert(selectionRange: l.SelectionRange): List[l.TextEdit] = {
    val range = selectionRange.getRange()
    val start = range.getStart()
    val end = range.getEnd()
    List(
      new TextEdit(new l.Range(start, start), s">>region>>"),
      new TextEdit(new l.Range(end, end), s"<<region<<")
    )
  }
}
