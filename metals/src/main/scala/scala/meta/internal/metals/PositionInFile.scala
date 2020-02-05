package scala.meta.internal.metals

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Location
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

case class PositionInFile(filePath: AbsolutePath, position: Position)

object PositionInFile {
  def locationToPositionInFile(location: Location): PositionInFile =
    PositionInFile(location.getUri.toAbsolutePath, location.getRange.getStart)
}
