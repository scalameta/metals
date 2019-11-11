package scala.meta.internal.metals

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.TextEdit
import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.jdk.CollectionConverters._

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
      positions.foreach {
        case (edit, pos) =>
          out.append(text, curr, pos.start)
          edit.getNewText().foreach {
            case '\t' => out.append("\\t")
            case ch => out.append(ch)
          }
          curr = pos.end
      }
      out.append(text, curr, text.length)
      out.toString
    }
  }
  def applyEdits(text: String, item: CompletionItem): String = {
    val edits = Option(item.getTextEdit).toList ++
      Option(item.getAdditionalTextEdits).toList.flatMap(_.asScala)
    applyEdits(text, edits)
  }
}
