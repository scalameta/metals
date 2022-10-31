package tests

import scala.collection.mutable.Buffer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.WorkspaceEdit

object TestRanges extends RangeReplace {

  def renderLocationsAsString(
      sourceFiles: Map[String, String],
      locations: List[Location],
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
      workspaceEdit: WorkspaceEdit,
  ): Option[String] = {
    val changes =
      Option(workspaceEdit.getChanges()).map(_.asScala).getOrElse(Buffer.empty)

    val documentChanges =
      Option(workspaceEdit.getDocumentChanges())
        .map(_.asScala.collect { case e if e.isLeft() => e.getLeft() })
        .getOrElse(Buffer.empty)
        .map(edit => (edit.getTextDocument().getUri, edit.getEdits))

    (changes ++ documentChanges).collectFirst {
      case (editFile, edits) if editFile.contains(file) =>
        TextEdits.applyEdits(code, edits.asScala.toList)
    }
  }
}
