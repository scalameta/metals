package tests

import org.eclipse.lsp4j.TextEdit
import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._

object TextEdits {
  def applyEdit(text: String, edit: TextEdit): String = {
    val input = Input.String(text)
    val pos = edit.getRange.toMeta(input)
    new java.lang.StringBuilder()
      .append(text, 0, pos.start)
      .append(edit.getNewText)
      .append(text, pos.end, text.length)
      .toString
  }
}
