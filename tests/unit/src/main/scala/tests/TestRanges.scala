package tests

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.WorkspaceEdit

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
    } yield file -> validLocations.foldLeft(code) { (base, location) =>
      replaceInRange(base, location.getRange)
    }
    resolved.toMap
  }

  def renderEditAsString(
      file: String,
      code: String,
      workspaceEdit: WorkspaceEdit
  ): Option[String] = {
    for {
      validLocations <-
        workspaceEdit
          .getDocumentChanges()
          .asScala
          .find(change =>
            change.isLeft &&
              change.getLeft.getTextDocument.getUri.contains(file)
          )
    } yield TextEdits.applyEdits(
      code,
      validLocations.getLeft.getEdits.asScala.toList
    )

  }
}
