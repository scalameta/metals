package tests

import scala.meta.inputs.Input
import scala.meta.internal.mtags.ScalametaCommonEnrichments._

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.Range

trait RangeReplace {

  def renderHighlightsAsString(
      code: String,
      highlights: List[DocumentHighlight]
  ): String = renderRangesAsString(code, highlights.map(_.getRange()))

  def renderRangesAsString(
      code: String,
      highlights: List[Range],
      alreadyAddedMarkings: List[(Int, Int)] = Nil,
      currentBase: Option[String] = None
  ): String = {
    highlights
      .foldLeft((currentBase.getOrElse(code), alreadyAddedMarkings)) {
        case ((base, alreadyAddedMarkings), range) =>
          replaceInRangeWithAdjustments(
            code,
            base,
            range,
            alreadyAddedMarkings
          )
      }
      ._1
  }

  protected def replaceInRange(
      base: String,
      range: Range,
      prefix: String = "<<",
      suffix: String = ">>"
  ): String =
    replaceInRangeWithAdjustments(base, base, range, List(), prefix, suffix)._1

  protected def replaceInRangeWithAdjustments(
      code: String,
      currentBase: String,
      range: Range,
      alreadyAddedMarkings: List[(Int, Int)],
      prefix: String = "<<",
      suffix: String = ">>"
  ): (String, List[(Int, Int)]) = {
    val input = Input.String(code)
    val pos = range
      .toMeta(input)
      .getOrElse(
        throw new RuntimeException(s"$range was not contained in file")
      )
    def adjustPosition(pos: Int) =
      alreadyAddedMarkings
        .filter { case (i, _) => i <= pos }
        .map(_._2)
        .fold(0)(_ + _) + pos
    val posStart = adjustPosition(pos.start)
    val posEnd = adjustPosition(pos.end)
    (
      new java.lang.StringBuilder()
        .append(currentBase, 0, posStart)
        .append(prefix)
        .append(currentBase, posStart, posEnd)
        .append(suffix)
        .append(currentBase, posEnd, currentBase.length)
        .toString,
      (pos.start, prefix.length) :: (
        pos.end,
        suffix.length
      ) :: alreadyAddedMarkings
    )
  }

}
