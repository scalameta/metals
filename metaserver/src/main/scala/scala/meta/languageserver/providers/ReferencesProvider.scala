package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import langserver.{types => l}
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.Uri
import langserver.types.Location

object ReferencesProvider extends LazyLogging {

  def references(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: l.Position,
      context: l.ReferenceContext
  ): List[Location] = {
    for {
      data <- symbolIndex.findReferences(uri, position.line, position.character)
      pos <- data.referencePositions(context.includeDeclaration)
      _ = logger.info(s"Found reference ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation
  }

}
