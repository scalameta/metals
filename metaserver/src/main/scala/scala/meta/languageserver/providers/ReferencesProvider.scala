package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import langserver.{types => l}
import langserver.messages.ReferencesResult
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.Uri

object ReferencesProvider extends LazyLogging {

  def references(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: l.Position,
      context: l.ReferenceContext
  ): ReferencesResult = {
    val locations = for {
      symbol <- symbolIndex
        .findSymbol(uri, position.line, position.character)
        .toList
      data <- symbolIndex.referencesData(symbol)
      pos <- data.referencePositions(context.includeDeclaration)
      _ = logger.info(s"Found reference ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation
    ReferencesResult(locations)
  }

}
