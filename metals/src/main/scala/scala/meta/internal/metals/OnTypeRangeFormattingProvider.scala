package scala.meta.internal.metals

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.onTypeRangeFormatters.IndentOnPaste
import scala.meta.internal.metals.onTypeRangeFormatters.IndentOnPaste.EndPosition
import scala.meta.internal.metals.onTypeRangeFormatters.IndentOnPaste.StartPosition
import scala.meta.internal.metals.onTypeRangeFormatters.MultilineString
import scala.meta.internal.metals.onTypeRangeFormatters.OnTypeFormatter
import scala.meta.internal.metals.onTypeRangeFormatters.OnTypeFormatterParams
import scala.meta.internal.metals.onTypeRangeFormatters.RangeFormatter
import scala.meta.internal.metals.onTypeRangeFormatters.RangeFormatterParams
import scala.meta.internal.metals.onTypeRangeFormatters.TokensParams
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

sealed abstract class OnTypeRangeFormattingProvider(buffers: Buffers) {

  protected def contribute(
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

  protected def withToken(
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
}

class OnTypeFormattingProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration
) extends OnTypeRangeFormattingProvider(buffers) {
  val enableStripMargin: Boolean =
    userConfig().enableStripMarginOnTypeFormatting

  // The order of which this is important to know which will first return the Edits
  val formatters: List[OnTypeFormatter] = List(
    MultilineString(enableStripMargin)
  )

  def format(
      params: DocumentOnTypeFormattingParams,
      trees: Trees
  ): List[TextEdit] = {
    val uri = params.getTextDocument.getUri.toAbsolutePath
    val doc = params.getTextDocument
    val range = new Range(params.getPosition, params.getPosition)
    val triggerChar = params.getCh
    val position = params.getPosition

    contribute(uri, doc, range, trees) {
      (sourceText, startPos, endPos, tokensOpt) =>
        val tokensParams = TokensParams(startPos, endPos, tokensOpt)
        val onTypeformatterParams =
          OnTypeFormatterParams(sourceText, position, triggerChar)
        formatters
          .foldLeft[Option[List[TextEdit]]](None) { (opt, formatter) =>
            opt.orElse(
              formatter.contribute(
                onTypeformatterParams,
                tokensParams
              )
            )
          }
          .getOrElse(Nil)
    }
  }
}

class rangeFormattingProvider(
    buffers: Buffers
) extends OnTypeRangeFormattingProvider(buffers) {
  val formatters: List[RangeFormatter] = List(
    // enableStripMargin is not used on rangeFormatting
    MultilineString(enableStripMargin = false),
    IndentOnPaste
  )

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
        val rangeFormatterParams =
          RangeFormatterParams(sourceText, range, formattingOptions)
        val tokensParams = TokensParams(startPos, endPos, tokensOpt)
        formatters
          .foldLeft[Option[List[TextEdit]]](None) { (opt, formatter) =>
            opt.orElse(
              formatter.contribute(
                rangeFormatterParams,
                tokensParams
              )
            )
          }
          .getOrElse(Nil)
    }
  }
}
