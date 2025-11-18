package scala.meta.internal.metals.formatting

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

case class RangeFormatterParams(
    path: AbsolutePath,
    projectRoot: AbsolutePath,
    token: CancelToken,
    sourceText: String,
    range: Range,
    formattingOptions: FormattingOptions,
    startPos: Position,
    endPos: Position,
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
    scalafmtProvider: FormattingProvider,
) {

  val formatters: List[RangeFormatter] = List(
    MultilineString(userConfig),
    IndentOnPaste(userConfig),
    new ScalafmtRangeFormatter(userConfig, scalafmtProvider, buffers, trees),
  )

  def format(
      params: DocumentRangeFormattingParams,
      projectRoot: AbsolutePath,
      token: CancelToken,
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
      val rangeFormatterParams = RangeFormatterParams(
        path,
        projectRoot,
        token,
        sourceText,
        range,
        formattingOptions,
        startPos,
        endPos,
        trees.tokenized(path),
      )
      formatters.acceptFirst(formater =>
        formater.contribute(rangeFormatterParams)
      )
    }

    edits.getOrElse(Nil)
  }
}
