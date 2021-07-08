package scala.meta.internal.metals.onTypeRangeFormatters

import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

sealed trait PositionTyped {
  type StartPosition = meta.Position
  type EndPosition = meta.Position
}

trait OnTypeFormatter extends PositionTyped {

  def contribute(
      onTypeformatterParams: OnTypeFormatterParams,
      tokensParams: TokensParams
  ): Option[List[TextEdit]] = None

}

trait RangeFormatter extends PositionTyped {
  def contribute(
      rangeFormatterParams: RangeFormatterParams,
      tokensParams: TokensParams
  ): Option[List[TextEdit]] = None
}

case class TokensParams(
    startPos: meta.Position,
    endPos: meta.Position,
    tokens: Option[Tokens]
)

trait FormatterParams {
  val sourceText: String
  val range: Range
  lazy val splitLines: Array[String] = sourceText.split("\\r?\\n")
}

case class OnTypeFormatterParams(
    sourceText: String,
    position: Position,
    triggerChar: String
) extends FormatterParams {
  val range = new Range(position, position)
}

case class RangeFormatterParams(
    sourceText: String,
    range: Range,
    formattingOptions: FormattingOptions
) extends FormatterParams
