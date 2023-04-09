package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.TextDocumentPositionParams

final class JavaDocumentHighlightProvider(
    definitionProvider: DefinitionProvider,
    semanticdbs: Semanticdbs,
) {

  def documentHighlight(
      params: TextDocumentPositionParams
  ): java.util.List[DocumentHighlight] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val result = semanticdbs.textDocument(source)

    val highlights = for {
      doc <- result.documentIncludingStale.toList
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params.getPosition,
        doc,
      )
      occ <- positionOccurrence.occurrence.toList
      curr <- doc.occurrences
      if curr.symbol == occ.symbol
      range <- curr.range
      revised <- positionOccurrence.distance.toRevised(range.toLsp)
      kind =
        if (curr.role.isDefinition) {
          DocumentHighlightKind.Write
        } else {
          DocumentHighlightKind.Read
        }
    } yield new DocumentHighlight(revised, kind)
    highlights.asJava
  }
}
