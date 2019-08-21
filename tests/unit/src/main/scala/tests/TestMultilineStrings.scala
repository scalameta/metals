package tests

import org.eclipse.lsp4j.{DocumentOnTypeFormattingParams, TextEdit}

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._

object TestMultilineStrings extends TestMultilineStrings
trait TestMultilineStrings {

  def renderAsString(
      code: String,
      params: DocumentOnTypeFormattingParams,
      edit: List[TextEdit]
  ): String = {
    edit.foldLeft(code) { (base, smallEdit) =>
      replaceInRange(base, params, smallEdit)
    }
  }

  protected def replaceInRange(
      base: String,
      params: DocumentOnTypeFormattingParams,
      edit: TextEdit
  ): String = {
    val input = Input.String(base)
    val text =
      if (edit != null) edit.getNewText
      else ""
    val pos = params.getPosition.toMeta(input)
    new java.lang.StringBuilder()
      .append(base, 0, pos.start)
      .append(text)
      .append(base, pos.start, base.length)
      .toString
  }

}
