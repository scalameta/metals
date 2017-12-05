package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import langserver.{types => l}
import langserver.messages.DocumentHighlightResult
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.ScalametaEnrichments._

object DocumentHighlightProvider extends LazyLogging {

  def highlight(
      symbolIndex: SymbolIndex,
      path: AbsolutePath,
      position: l.Position
  ): DocumentHighlightResult = {
    logger.info(s"Document highlight in ${path}")
    val locations = for {
      name <- symbolIndex
        .resolveName(path, position.line, position.character)
        .toList
      data <- symbolIndex.symbolIndexer.get(name.symbol).toList
      _ = logger.info(s"Highlighting symbol `${data.name}: ${data.signature}`")
      pos <- data.referencePositions(withDefinition = true)
      if Uri.toPath(pos.uri) == Some(path)
      _ = logger.debug(s"Found highlight at [${pos.range.get.pretty}]")
    } yield pos.toLocation
    // TODO(alexey) add DocumentHighlightKind: Text (default), Read, Write
    DocumentHighlightResult(locations)
  }

}
