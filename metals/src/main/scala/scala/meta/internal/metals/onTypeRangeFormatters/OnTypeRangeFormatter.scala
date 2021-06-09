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
      sourceText: String,
      splitLines: Array[String],
      startPos: StartPosition,
      endPos: EndPosition,
      triggerChar: String,
      position: Position,
      tokensOpt: Option[Tokens]
  ): Option[List[TextEdit]] = None

}

trait RangeFormatter extends PositionTyped {
  def contribute(
      sourceText: String,
      range: Range,
      splitLines: Array[String],
      startPos: StartPosition,
      endPos: EndPosition,
      formattingOptions: FormattingOptions,
      tokens: Option[Tokens]
  ): Option[List[TextEdit]] = None
}
