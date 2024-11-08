package scala.meta.internal.metals

import scala.meta.Position
import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.ScalametaCommonEnrichments._

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.TextEdit

/**
 * Client implementation of how to interpret `TextEdit` from LSP, used for testing purposes.
 */
object TextEdits {
  def applyEdits(text: String, edits: List[TextEdit]): String = {
    if (edits.isEmpty) text
    else {
      val input = Input.String(text)
      val positions: List[(TextEdit, Position)] = edits
        .map(edit => (edit, edit.getRange.toMeta(input)))
        .collect { case (edit, Some(pos)) =>
          edit -> pos
        }
        .sortWith { case ((_, pos1), (_, pos2)) =>
          if (pos1.start == pos2.start) pos1.end < pos2.end
          else pos1.start < pos2.start

        }
      var curr = 0
      val out = new java.lang.StringBuilder()
      positions.foreach { case (edit, pos) =>
        out.append(text, curr, pos.start)
        out.append(edit.getNewText())
        curr = pos.end
      }
      out.append(text, curr, text.length)
      out.toString
    }
  }
  def applyEdits(text: String, item: CompletionItem): String = {
    val edits = item.getLeftTextEdit().toList ++
      Option(item.getAdditionalTextEdits).toList.flatMap(_.asScala)
    applyEdits(text, edits)
  }
}
