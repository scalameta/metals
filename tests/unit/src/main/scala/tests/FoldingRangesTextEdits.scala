package tests

import java.util
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}
import scala.collection.JavaConverters._

object FoldingRangesTextEdits {
  def apply(ranges: util.List[l.FoldingRange]): List[l.TextEdit] = {
    ranges.asScala.flatMap(convert).toList
  }

  private def convert(range: l.FoldingRange): List[l.TextEdit] = {
    val start = new l.Position(range.getStartLine, range.getStartCharacter)
    val end = new l.Position(range.getEndLine, range.getEndCharacter)
    List(
      new TextEdit(new l.Range(start, start), s">>${range.getKind}>>"),
      new TextEdit(new l.Range(end, end), s"<<${range.getKind}<<")
    )
  }
}
