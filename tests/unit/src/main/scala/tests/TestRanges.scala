package tests

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.WorkspaceEdit
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits

object TestRanges extends RangeReplace {

  def renderHighlightsAsString(
      code: String,
      highlights: List[DocumentHighlight]
  ): String = {
    highlights.foldLeft(code) { (base, highlight) =>
      replaceInRange(base, highlight.getRange)
    }
  }

  def renderLocationsAsString(
      sourceFiles: Map[String, String],
      locations: List[Location]
  ): Map[String, String] = {
    val resolved = for {
      (file, code) <- sourceFiles.toSeq
      validLocations = locations.filter(_.getUri().contains(file))
    } yield
      file -> validLocations.foldLeft(code) { (base, location) =>
        replaceInRange(base, location.getRange)
      }
    resolved.toMap
  }

  def renderEditAsStrings(
      sourceFiles: Map[String, String],
      workspaceEdit: WorkspaceEdit
  ): Map[String, String] = {
    val resolved = for {
      (file, code) <- sourceFiles.toSeq
      (_, validLocations) <- workspaceEdit
        .getChanges()
        .asScala
        .find(_._1.contains(file))
    } yield file -> TextEdits.applyEdits(code, validLocations.asScala.toList)
    sourceFiles ++ resolved.toMap
  }
}
