package scala.meta.internal.metals.formatting

import scala.meta.internal.metals.formatting.RangeFormatter
import scala.meta.internal.metals.formatting.RangeFormatterParams
import scala.meta.internal.metals.scalacli.DependencyConverter

import org.eclipse.{lsp4j => l}

object ScalaCliDependencyRangeFormatter extends RangeFormatter {

  override def contribute(
      range: RangeFormatterParams
  ): Option[List[l.TextEdit]] = {

    val line = range.sourceText.substring(
      range.startPos.start - range.startPos.startColumn,
      range.endPos.end,
    )
    DependencyConverter
      .convertSbtToMillStyleIfPossible(
        line
      )
      .map(converted =>
        new l.TextEdit(range.range, converted.millStyleDependency)
      )
      .map(List(_))

  }

}
