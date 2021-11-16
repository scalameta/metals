package scala.meta.internal.metals

import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._

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
      val positions = edits
        .map(edit => edit -> edit.getRange.toMeta(input))
        .sortBy(_._2.start)
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
