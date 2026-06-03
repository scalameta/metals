package tests

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

object InlineValuesTextEdits {
  def apply(
      code: String,
      inlineValues: Seq[l.InlineValue],
  ): Iterable[l.TextEdit] = {
    val input = Input.String(code)
    inlineValues.map { inlineValue =>
      val (range, label) =
        if (inlineValue.isInlineValueText()) {
          val value = inlineValue.getInlineValueText()
          value.getRange() -> value.getText()
        } else if (inlineValue.isInlineValueVariableLookup()) {
          val value = inlineValue.getInlineValueVariableLookup()
          val variableName = Option(value.getVariableName())
            .orElse(value.getRange().toMeta(input).map(_.text))
            .getOrElse("")
          value.getRange() -> variableName
        } else {
          val value = inlineValue.getInlineValueEvaluatableExpression()
          val expression = Option(value.getExpression())
            .orElse(value.getRange().toMeta(input).map(_.text))
            .getOrElse("")
          value.getRange() -> expression
        }

      new l.TextEdit(
        new l.Range(range.getEnd(), range.getEnd()),
        s"<<$label>>",
      )
    }
  }
}
