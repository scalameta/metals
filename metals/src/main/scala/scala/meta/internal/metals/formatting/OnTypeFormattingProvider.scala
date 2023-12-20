package scala.meta.internal.metals.formatting

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

case class OnTypeFormatterParams(
    sourceText: String,
    position: Position,
    triggerChar: String,
    startPos: meta.Position,
    endPos: meta.Position,
    tokens: Option[Tokens],
) extends FormatterParams {
  lazy val splitLines: Array[String] = sourceText.split("\\r?\\n")
  val range = new Range(position, position)
}

abstract class OnTypeFormatter {
  def contribute(
      onTypeformatterParams: OnTypeFormatterParams
  ): Option[List[TextEdit]]
}

class OnTypeFormattingProvider(
    buffers: Buffers,
    trees: Trees,
    userConfig: () => UserConfiguration,
) {

  // The order of which this is important to know which will first return the Edits
  val formatters: List[OnTypeFormatter] = List(
    MultilineString(userConfig)
  )

  def format(
      params: DocumentOnTypeFormattingParams
  ): List[TextEdit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val range = new Range(params.getPosition, params.getPosition)
    val triggerChar = params.getCh
    val position = params.getPosition()

    val edits = for {
      sourceText <- buffers.get(path)
      virtualFile = Input.VirtualFile(path.toURI.toString(), sourceText)
      startPos <- range.getStart.toMeta(virtualFile)
      endPos <- range.getEnd.toMeta(virtualFile)
    } yield {
      val tokensOpt = trees.tokenized(virtualFile).toOption
      val onTypeformatterParams =
        OnTypeFormatterParams(
          sourceText,
          position,
          triggerChar,
          startPos,
          endPos,
          tokensOpt,
        )
      formatters.acceptFirst(formater =>
        formater.contribute(onTypeformatterParams)
      )
    }
    edits.getOrElse(Nil)
  }
}
