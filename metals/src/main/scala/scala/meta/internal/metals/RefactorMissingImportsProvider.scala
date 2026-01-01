package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.MissingSymbolDiagnostic
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

class RefactorMissingImportsProvider(
    compilers: Compilers,
    buildTargets: BuildTargets,
    buffers: Buffers,
)(implicit ec: ExecutionContext) {

  /**
   * Enhance workspace edits with missing import fixes.
   *
   * After the initial refactoring edits (e.g., package rename and reference updates),
   * this method checks for missing symbols using outline compilation and adds
   * import edits to fix them.
   *
   * @param edit The workspace edit from the initial refactoring
   * @param affectedFiles The files that were affected by the refactoring
   * @param cancelToken Token to cancel the operation
   * @return Enhanced workspace edit with additional import fixes
   */
  def enhanceWithMissingImports(
      edit: WorkspaceEdit,
      affectedFiles: List[AbsolutePath],
      cancelToken: CancelToken,
  ): Future[WorkspaceEdit] = {
    val importFixFutures = affectedFiles.map { path =>
      val currentEdits = Option(edit.getChanges)
        .flatMap(changes => Option(changes.get(path.toURI.toString)))
        .map(_.asScala.toList)
        .getOrElse(Nil)

      buffers.get(path) match {
        case Some(content) =>
          val newContent = TextEdits.applyEdits(content, currentEdits)
          fixMissingImports(path, newContent, cancelToken).map { importEdits =>
            path.toURI.toString -> (currentEdits ++ importEdits)
          }
        case None =>
          Future.successful(path.toURI.toString -> currentEdits)
      }
    }

    Future.sequence(importFixFutures).map { fileEdits =>
      val allChanges = fileEdits.toMap.view.mapValues(_.asJava).toMap.asJava
      new WorkspaceEdit(allChanges)
    }
  }

  /**
   * Fix missing imports in a file after refactoring edits have been applied.
   *
   * Uses outline compilation to detect missing symbols and auto-imports to fix them.
   *
   * @param path The path to the file (may be virtual/new path after move)
   * @param content The content of the file after initial refactoring edits
   * @param cancelToken Token to cancel the operation
   * @return List of text edits to add missing imports
   */
  def fixMissingImports(
      path: AbsolutePath,
      content: String,
      cancelToken: CancelToken,
  ): Future[List[TextEdit]] = {
    val isScala3 =
      buildTargets.scalaVersion(path).exists(ScalaVersions.isScala3Version)

    val MissingSymbol = new MissingSymbolDiagnostic(isScala3)
    compilers
      .didChange(
        path,
        shouldReturnDiagnostics = true,
        content = Some(content),
      )
      .flatMap { diagnostics =>
        val missingSymbols = diagnostics.collect {
          case d @ MissingSymbol(name, isNotAMember) =>
            scribe.debug(
              s"Missing symbol after refactoring: $name at ${d.getRange()}"
            )
            (d, name, isNotAMember)
        }

        if (missingSymbols.isEmpty) {
          Future.successful(Nil)
        } else {
          val importFutures = missingSymbols.map { case (diagnostic, name, _) =>
            val params = new TextDocumentPositionParams(
              new lsp4j.TextDocumentIdentifier(path.toURI.toString),
              diagnostic.getRange().getStart(),
            )
            compilers
              .autoImports(
                params,
                name,
                findExtensionMethods = isScala3,
                cancelToken,
              )
              .map { autoImports =>
                autoImports.asScala.headOption
                  .map(_.edits().asScala.toList)
                  .getOrElse(Nil)
              }
          }

          Future
            .sequence(importFutures)
            .map(_.flatten.distinct.toList)
            .map(mergeImportEdits)
        }
      }
  }

  private def mergeImportEdits(edits: List[TextEdit]): List[TextEdit] = {
    edits
      .groupBy(edit => (edit.getRange().getStart(), edit.getRange().getEnd()))
      .map { case (_, editsWithSameRange) =>
        if (editsWithSameRange.size > 1) {
          new TextEdit(
            editsWithSameRange.head.getRange(),
            editsWithSameRange
              .map(_.getNewText())
              .sorted
              .mkString
              .replace("\n\n", "\n"),
          )
        } else {
          editsWithSameRange.head
        }
      }
      .toList
  }
}
