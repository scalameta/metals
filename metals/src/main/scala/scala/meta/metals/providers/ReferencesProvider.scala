package scala.meta.metals.providers

import com.typesafe.scalalogging.LazyLogging
import scala.meta.metals.search.SymbolIndex
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.Uri
import scala.meta.lsp.Location
import scala.meta.lsp.Position
import scala.meta.lsp.ReferenceContext

object ReferencesProvider extends LazyLogging {

  def references(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: Position,
      context: ReferenceContext
  ): List[Location] = {
    for {
      data <- symbolIndex.findReferences(uri, position.line, position.character)
      pos <- data.referencePositions(context.includeDeclaration)
      _ = logger.info(s"Found reference ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation
  }

}
