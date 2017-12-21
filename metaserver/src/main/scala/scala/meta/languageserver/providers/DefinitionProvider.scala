package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import langserver.{types => l}
import langserver.messages.DefinitionResult
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.Uri

object DefinitionProvider extends LazyLogging {

  def definition(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: l.Position,
      tempSourcesDir: AbsolutePath
  ): DefinitionResult = {
    val locations = for {
      data <- symbolIndex.findDefinition(uri, position.line, position.character)
      pos <- data.definition
      _ = logger.info(s"Found definition ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation.toNonJar(tempSourcesDir)
    DefinitionResult(locations.toList)
  }

}
