package scala.meta.metals.providers

import scala.meta.metals.Uri
import scala.meta.metals.search.SymbolIndex
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.lsp.DocumentHighlight
import scala.meta.lsp.Position

object DocumentHighlightProvider {
  def empty: List[DocumentHighlight] = Nil

  def highlight(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: Position
  ): List[DocumentHighlight] = {
    scribe.info(s"Document highlight in $uri")
    for {
      data <- symbolIndex.findReferences(uri, position.line, position.character)
      _ = scribe.info(s"Highlighting symbol `${data.name}: ${data.signature}`")
      pos <- data.referencePositions(withDefinition = true)
      if pos.uri == uri.value
      _ = scribe.debug(s"Found highlight at [${pos.range.get.pretty}]")
      // TODO(alexey) add DocumentHighlightKind: Text (default), Read, Write
    } yield DocumentHighlight(pos.range.get.toRange)
  }

}
