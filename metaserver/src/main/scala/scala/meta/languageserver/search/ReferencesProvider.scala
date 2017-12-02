package scala.meta.languageserver.search

import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import langserver.{types => l}
import langserver.messages.ReferencesResult
import scala.meta.languageserver.ScalametaEnrichments._

object ReferencesProvider extends LazyLogging {

  def references(
      symbolIndex: SymbolIndex,
      path: AbsolutePath,
      position: l.Position,
      context: l.ReferenceContext
  ): ReferencesResult = {
    val locations = for {
      symbol <- symbolIndex
        .findSymbol(path, position.line, position.character)
        .toList
      data <- symbolIndex.referencesData(symbol)
      pos <- data.referencePositions(context.includeDeclaration)
      _ = logger.info(s"Found reference ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation
    ReferencesResult(locations)
  }

}
