package tests

import org.eclipse.lsp4j.Range

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._

trait RangeReplace {

  protected def replaceInRange(
      base: String,
      range: Range,
      prefix: String = "<<",
      suffix: String = ">>"
  ): String = {
    val input = Input.String(base)
    val pos = range.toMeta(input)
    new java.lang.StringBuilder()
      .append(base, 0, pos.start)
      .append(prefix)
      .append(base, pos.start, pos.end)
      .append(suffix)
      .append(base, pos.end, base.length)
      .toString
  }

}
