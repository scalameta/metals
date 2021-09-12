package scala.meta.internal.metals.formatting

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

case class RangeFormatterParams(
    sourceText: String,
    range: Range,
    formattingOptions: FormattingOptions,
    startPos: meta.Position,
    endPos: meta.Position,
    tokens: Option[Tokens]
) extends FormatterParams {
  lazy val splitLines: Array[String] = sourceText.split("\\r?\\n")
}

trait RangeFormatter {
  def contribute(
      rangeFormatterParams: RangeFormatterParams
  ): Option[List[TextEdit]] = None
}

class RangeFormattingProvider(
    buffers: Buffers,
    trees: Trees
) {
  val defaultFormatters: List[RangeFormatter] = List(
    // enableStripMargin is not used on rangeFormatting
    MultilineString(() => UserConfiguration())
  )

  def format(
      params: DocumentRangeFormattingParams,
      formatters: List[RangeFormatter] = defaultFormatters
  ): List[TextEdit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val range = params.getRange
    val formattingOptions = params.getOptions
    buffers
      .get(path)
      .map { sourceText =>
        val virtualFile = Input.VirtualFile(path.toString(), sourceText)
        val startPos = range.getStart.toMeta(virtualFile)
        val endPos = range.getEnd.toMeta(virtualFile)
        val tokensOpt = trees.tokenized(virtualFile).toOption
        val rangeFormatterParams =
          RangeFormatterParams(
            sourceText,
            range,
            formattingOptions,
            startPos,
            endPos,
            tokensOpt
          )
        formatters.acceptFirst(formater =>
          formater.contribute(rangeFormatterParams)
        )
      }
      .getOrElse(Nil)
  }

  def formatIdentOnPaste(
      uri: String,
      range: Range,
      tabSize: Int,
      isInsertSpaces: Boolean
  ): List[TextEdit] = {
    val textDocumentId = new TextDocumentIdentifier(uri)
    val formatOpts = new FormattingOptions(tabSize, isInsertSpaces)
    val params =
      new DocumentRangeFormattingParams(textDocumentId, formatOpts, range)
    format(params, formatters = List(IndentOnPaste))
  }
}
