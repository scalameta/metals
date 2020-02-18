package scala.meta.internal.metals

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Location
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

case class FilePosition(filePath: AbsolutePath, position: Position)

object FilePosition {
  def locationToFilePosition(location: Location): FilePosition =
    FilePosition(location.getUri.toAbsolutePath, location.getRange.getStart)
}
