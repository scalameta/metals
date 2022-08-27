package tests

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.Range

trait RangeReplace {

  def renderHighlightsAsString(
      code: String,
      highlights: List[DocumentHighlight],
  ): String = {
    highlights.foldLeft(code) { (base, highlight) =>
      replaceInRange(base, highlight.getRange)
    }
  }

  protected def replaceInRange(
      base: String,
      range: Range,
      prefix: String = "<<",
      suffix: String = ">>",
  ): String = {
    val input = Input.String(base)
    val pos = range
      .toMeta(input)
      .getOrElse(
        throw new RuntimeException(s"$range was not contained in file")
      )
    new java.lang.StringBuilder()
      .append(base, 0, pos.start)
      .append(prefix)
      .append(base, pos.start, pos.end)
      .append(suffix)
      .append(base, pos.end, base.length)
      .toString
  }

}
