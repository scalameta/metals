package scala.meta.internal.metals.formatting

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

case class RangeFormatterParams(
    sourceText: String,
    range: Range,
    formattingOptions: FormattingOptions,
    startPos: meta.Position,
    endPos: meta.Position,
    tokens: Option[Tokens],
) extends FormatterParams {
  lazy val splitLines: Array[String] = sourceText.split("\\r?\\n")
}

trait RangeFormatter {
  def contribute(
      rangeFormatterParams: RangeFormatterParams
  ): Option[List[TextEdit]]
}

class RangeFormattingProvider(
    buffers: Buffers,
    trees: Trees,
    userConfig: () => UserConfiguration,
) {
  val formatters: List[RangeFormatter] = List(
    MultilineString(userConfig),
    IndentOnPaste(userConfig),
  )

  def format(
      params: DocumentRangeFormattingParams
  ): List[TextEdit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val range = params.getRange
    val formattingOptions = params.getOptions
    val edits = for {
      sourceText <- buffers.get(path)
      virtualFile = Input.VirtualFile(path.toURI.toString(), sourceText)
      startPos <- range.getStart.toMeta(virtualFile)
      endPos <- range.getEnd.toMeta(virtualFile)
    } yield {
      val tokensOpt = trees.tokenized(virtualFile).toOption
      val rangeFormatterParams =
        RangeFormatterParams(
          sourceText,
          range,
          formattingOptions,
          startPos,
          endPos,
          tokensOpt,
        )
      formatters.acceptFirst(formater =>
        formater.contribute(rangeFormatterParams)
      )
    }

    edits.getOrElse(Nil)
  }
}
