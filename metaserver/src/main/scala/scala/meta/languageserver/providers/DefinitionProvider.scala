package scala.meta.languageserver.providers

import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import com.typesafe.scalalogging.LazyLogging
import langserver.types.Location
import langserver.{types => l}
import org.langmeta.io.AbsolutePath

object DefinitionProvider extends LazyLogging {

  def definition(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: l.Position,
      tempSourcesDir: AbsolutePath
  ): List[Location] = {
    val locations = for {
      data <- symbolIndex.findDefinition(uri, position.line, position.character)
      pos <- data.definition
      _ = logger.info(s"Found definition ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation.toNonJar(tempSourcesDir)
    locations.toList
  }

}
