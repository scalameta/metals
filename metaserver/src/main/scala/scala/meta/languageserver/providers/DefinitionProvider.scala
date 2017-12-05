package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import langserver.{types => l}
import langserver.messages.DefinitionResult
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.ScalametaEnrichments._

object DefinitionProvider extends LazyLogging {

  def definition(
      symbolIndex: SymbolIndex,
      path: AbsolutePath,
      position: l.Position,
      tempSourcesDir: AbsolutePath
  ): DefinitionResult = {
    val locations = for {
      symbol <- symbolIndex.findSymbol(
        path,
        position.line,
        position.character
      )
      data <- symbolIndex.definitionData(symbol)
      pos <- data.definition
      _ = logger.info(s"Found definition ${pos.pretty} ${data.symbol}")
    } yield pos.toLocation.toNonJar(tempSourcesDir)
    DefinitionResult(locations.toList)
  }

}
