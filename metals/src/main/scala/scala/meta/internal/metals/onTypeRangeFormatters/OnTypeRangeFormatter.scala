package scala.meta.internal.metals.onTypeRangeFormatters

import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

trait OnTypeRangeFormatter {
  type StartPosition = meta.Position
  type EndPosition = meta.Position

  def onRangeContribute(
      sourceText: String,
      range: Range,
      splitLines: Array[String],
      startPos: StartPosition,
      endPos: EndPosition,
      formattingOptions: FormattingOptions,
      tokens: Option[Tokens]
  ): Option[List[TextEdit]] = None

  def onTypeContribute(
      sourceText: String,
      range: Range,
      splitLines: Array[String],
      startPos: StartPosition,
      endPos: EndPosition,
      triggerChar: String,
      position: Position,
      enableStripMargin: Boolean,
      tokensOpt: Option[Tokens]
  ): Option[List[TextEdit]] = None

}
