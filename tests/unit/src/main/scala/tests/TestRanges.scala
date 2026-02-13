package tests

import java.util

import scala.collection.mutable.Buffer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

object TestRanges extends RangeReplace {

  def renderLocationsAsString(
      sourceFiles: Map[String, String],
      locations: List[Location],
  ): Map[String, String] = {
    val resolved = for {
      (file, code) <- sourceFiles.toSeq
      validLocations = locations.filter(_.getUri().contains(file))
    } yield file -> validLocations
      .foldLeft((code, List.empty[(Int, Int)])) {
        case ((base, alreadyAddedMarkings), location) =>
          replaceInRangeWithAdjustments(
            code,
            base,
            location.getRange,
            alreadyAddedMarkings,
          )
      }
      ._1
    resolved.toMap
  }

  def renderEditAsString(
      file: String,
      code: String,
      workspaceEdit: WorkspaceEdit,
  ): Option[String] = {
    val documentChanges =
      Option(workspaceEdit.getDocumentChanges())
        .map(_.asScala.collect { case e if e.isLeft() => e.getLeft() })
        .getOrElse(Buffer.empty)
        .map(edit => (edit.getTextDocument().getUri, edit.getEdits))

    val fromDocumentChanges =
      documentChanges.collectFirst {
        case (editFile, edits) if editFile.contains(file) =>
          val textEdits = edits.asScala
            .flatMap(e => if (e.isLeft()) Some(e.getLeft()) else None)
            .toList
          TextEdits.applyEdits(code, textEdits)
      }

    val fromChanges =
      Option(workspaceEdit.getChanges())
        .map(_.asScala)
        .getOrElse(Map.empty[String, util.List[TextEdit]])
        .find { case (editFile, _) => editFile.contains(file) }
        .map { case (_, edits) =>
          TextEdits.applyEdits(code, edits.asScala.toList)
        }

    fromDocumentChanges.orElse(fromChanges)
  }
}
