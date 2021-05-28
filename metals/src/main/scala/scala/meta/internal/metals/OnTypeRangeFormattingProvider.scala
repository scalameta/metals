package scala.meta.internal.metals

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.onTypeRangeFormatters.IndentOnPaste
import scala.meta.internal.metals.onTypeRangeFormatters.MultilineString
import scala.meta.internal.metals.onTypeRangeFormatters.OnTypeRangeFormatter
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

class OnTypeRangeFormattingProvider(
    buffers: Buffers,
    trees: Trees,
    userConfig: () => UserConfiguration
) {

  type StartPosition = meta.Position
  type EndPosition = meta.Position
  // The order of which this is important to know which will first return the Edits
  val formatters: List[OnTypeRangeFormatter] = List(
    MultilineString(),
    IndentOnPaste()
  )

  def format(
      params: DocumentOnTypeFormattingParams,
      trees: Trees
  ): List[TextEdit] = {
    val uri = params.getTextDocument.getUri.toAbsolutePath
    val doc = params.getTextDocument
    val range = new Range(params.getPosition, params.getPosition)
    val enableStripMargin = userConfig().enableStripMarginOnTypeFormatting
    val triggerChar = params.getCh
    val position = params.getPosition

    contribute(uri, doc, range, trees) {
      (sourceText, startPos, endPos, tokensOpt) =>
        val splitLines = sourceText.split('\n')
        formatters
          .foldLeft[Option[List[TextEdit]]](None) { (opt, formatter) =>
            opt.orElse(
              formatter.onTypeContribute(
                sourceText,
                range,
                splitLines,
                startPos,
                endPos,
                triggerChar,
                position,
                enableStripMargin,
                tokensOpt
              )
            )
          }
          .getOrElse(Nil)
    }
  }

  private def contribute(
      uri: AbsolutePath,
      textId: TextDocumentIdentifier,
      range: Range,
      trees: Trees
  )(
      fn: (String, StartPosition, EndPosition, Option[Tokens]) => List[TextEdit]
  ): List[TextEdit] = {
    buffers
      .get(uri)
      .map { sourceText =>
        withToken(textId, sourceText, range, trees) {
          (startPos, endPos, tokens) =>
            fn(sourceText, startPos, endPos, tokens)
        }
      }
      .getOrElse(Nil)
  }

  private def withToken(
      textId: TextDocumentIdentifier,
      sourceText: String,
      range: Range,
      trees: Trees
  )(
      fn: (
          StartPosition,
          EndPosition,
          Option[Tokens]
      ) => List[TextEdit]
  ): List[TextEdit] = {
    val source = textId.getUri.toAbsolutePath
    if (source.exists) {
      val virtualFile = Input.VirtualFile(source.toString(), sourceText)
      val startPos = range.getStart.toMeta(virtualFile)
      val endPos = range.getEnd.toMeta(virtualFile)
      val tokens = trees.tokenized(virtualFile).toOption
      fn(startPos, endPos, tokens)
    } else Nil
  }

  def format(
      params: DocumentRangeFormattingParams,
      trees: Trees
  ): List[TextEdit] = {
    val uri = params.getTextDocument.getUri.toAbsolutePath
    val range = params.getRange
    val doc = params.getTextDocument
    val formattingOptions = params.getOptions

    contribute(uri, doc, range, trees) {
      (sourceText, startPos, endPos, tokensOpt) =>
        val splitLines = sourceText.split('\n')
        formatters
          .foldLeft[Option[List[TextEdit]]](None) { (opt, formatter) =>
            opt.orElse(
              formatter.onRangeContribute(
                sourceText,
                range,
                splitLines,
                startPos,
                endPos,
                formattingOptions,
                tokensOpt
              )
            )
          }
          .getOrElse(Nil)
    }

  }
}
