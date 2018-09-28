package scala.meta.metals.providers

import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.Uri
import scala.meta.lsp.Location
import scala.meta.lsp.Position
import scala.meta.metals.search.SymbolIndex
import org.langmeta.io.AbsolutePath

object DefinitionProvider {

  def definition(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: Position,
      tempSourcesDir: AbsolutePath
  ): List[Location] = {
    val locations = for {
      data <- symbolIndex.findDefinition(uri, position.line, position.character)
      pos <- data.definition
      _ = scribe.info(s"Found definition ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation.toNonJar(tempSourcesDir)
    locations.toList
  }

}
