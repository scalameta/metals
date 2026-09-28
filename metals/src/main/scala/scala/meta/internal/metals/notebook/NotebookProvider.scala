package scala.meta.internal.metals.notebook

import java.net.URI
import java.nio.file.Paths
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.metals.AdjustLspData
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TargetData
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeNotebookDocumentParams
import org.eclipse.lsp4j.DidCloseNotebookDocumentParams
import org.eclipse.lsp4j.DidOpenNotebookDocumentParams
import org.eclipse.lsp4j.DidSaveNotebookDocumentParams
import org.eclipse.lsp4j.NotebookCellKind
import org.eclipse.lsp4j.NotebookDocumentChangeEventCellStructure
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextEdit

/**
 * Gives Scala notebook cells (`vscode-notebook-cell:` documents, see
 * https://github.com/scalameta/metals-feature-requests/issues/236) cross-cell
 * language support: [[combinedAdjustments]] concatenates a notebook's cells
 * into one virtual script so a cell can see imports/vals from earlier ones,
 * and [[triggerDiagnostics]] typechecks that concatenation and fans the
 * resulting diagnostics back out per cell. Once a notebook is associated
 * with a build target (see [[reregisterTargetData]]), its cells see that
 * target's real classpath instead of just the standard library.
 *
 * Each cell gets a synthetic, never-written-to-disk `.sc` path (see
 * [[NotebookProvider.cellPath]]) that is a pure function of its uri, so
 * `toAbsolutePath` can resolve it with no registry lookup.
 *
 * `didOpen`/`didChange`/`didClose` return the cell paths that were opened or
 * closed rather than reaching back out into the parsed-trees cache
 * themselves; the caller (`MetalsLspService`) owns that cache and feeds it.
 *
 * Deliberately out of scope: executing cells (same non-goal as the design
 * doc at https://github.com/scalameta/metals/issues/4434).
 */
final class NotebookProvider(
    buffers: Buffers,
    languageClient: MetalsLanguageClient,
    compilers: () => Compilers,
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext) {
  import NotebookProvider._

  private val notebooks = TrieMap.empty[AbsolutePath, Vector[AbsolutePath]]
  private val cells = TrieMap.empty[AbsolutePath, CellRef]
  private val combinedCache =
    TrieMap.empty[AbsolutePath, CombinedScript]
  private val registeredData = TrieMap.empty[AbsolutePath, TargetData]

  def didOpen(
      params: DidOpenNotebookDocumentParams
  ): CellChanges = {
    val result = for {
      ipynbPath <- params.getNotebookDocument.getUri.toAbsolutePathSafe
      languageById =
        params.getCellTextDocuments.asScala.toMapBy(_.getUri, _.getLanguageId)
      // The full cell order, any kind/language: `applyStructureChange`'s
      // `arrayChange.getStart`/`getDeleteCount` index into *this* array, so
      // it has to mirror the client's array shape exactly, not just the
      // Scala cells we otherwise care about.
      order = params.getNotebookDocument.getCells.asScala.iterator
        .map(_.getDocument)
        .toVector
      scalaUris = params.getNotebookDocument.getCells.asScala.iterator
        .filter(cell => cell.getKind == NotebookCellKind.Code)
        .map(_.getDocument)
        .filter(uri => languageById.get(uri) contains "scala")
        .toSet
      initialText = params.getCellTextDocuments.asScala
        .toMapBy(_.getUri, _.getText)
    } yield {
      val closed = forgetNotebook(ipynbPath)
      val opened = registerCells(ipynbPath, order, scalaUris, initialText)
      reregisterTargetData(ipynbPath)
      triggerDiagnostics(ipynbPath)
      CellChanges(opened, closed)
    }
    result.getOrElse(CellChanges.empty)
  }

  def didChange(
      params: DidChangeNotebookDocumentParams
  ): CellChanges = {
    val result = for {
      ipynbPath <- params.getNotebookDocument.getUri.toAbsolutePathSafe
    } yield {
      val cellsChange =
        Option(params.getChange).flatMap(c => Option(c.getCells))

      val structureChanges = (for {
        cellsChange <- cellsChange
        structure <- Option(cellsChange.getStructure)
      } yield applyStructureChange(ipynbPath, structure)).getOrElse(
        CellChanges.empty
      )

      val changedPaths = for {
        cellsChange <- cellsChange.toVector
        textContents <- Option(cellsChange.getTextContent).toVector
        textContent <- textContents.asScala
        path = uriToPath(textContent.getDocument.getUri)
        // The selector isn't a runtime guard: a client can send a text
        // change for a cell that isn't (or isn't yet) one of our registered
        // Scala cells, e.g. a markdown cell. Ignore it rather than feeding
        // a cell `computeCombined` never reads into `buffers`.
        if cells.contains(path)
      } yield {
        val current = buffers.get(path).getOrElse("")
        val updated = textContent.getChanges.asScala.foldLeft(current) {
          (text, change) =>
            change.getRange match {
              case null => change.getText
              case range =>
                TextEdits.applyEdits(
                  text,
                  List(new TextEdit(range, change.getText)),
                )
            }
        }
        buffers.put(path, updated)
        combinedCache.remove(ipynbPath)
        path
      }

      triggerDiagnostics(ipynbPath)
      structureChanges.copy(opened = structureChanges.opened ++ changedPaths)
    }
    result.getOrElse(CellChanges.empty)
  }

  def didClose(params: DidCloseNotebookDocumentParams): CellChanges =
    params.getNotebookDocument.getUri.toAbsolutePathSafe
      .map(ipynbPath => CellChanges(Vector.empty, forgetNotebook(ipynbPath)))
      .getOrElse(CellChanges.empty)

  def didSave(@annotation.unused params: DidSaveNotebookDocumentParams): Unit =
    ()

  /**
   * Called once a build import finishes, in case a build target has become
   * available for a notebook that was opened before any target existed, or
   * a previous association's target disappeared in the reload.
   */
  def retryAssociations(): Unit =
    for (ipynbPath <- notebooks.keysIterator) {
      reregisterTargetData(ipynbPath)
      triggerDiagnostics(ipynbPath)
    }

  /**
   * The build target `ipynbPath`'s cells should resolve against, picked the
   * same way any other ambiguous file is: prefer a location-compatible
   * candidate from `BuildTargets.sourceBuildTargets` if the notebook sits
   * under one's source root (same as `inverseSources`'s own preference),
   * otherwise the highest-scored candidate across the workspace by
   * `BuildTargets.buildTargetsOrder` (same as `inferBuildTarget`). Recomputed
   * on every call rather than cached, so a build reload that adds, removes,
   * or reorders targets is picked up automatically.
   */
  private def bestTarget(
      ipynbPath: AbsolutePath
  ): Option[b.BuildTargetIdentifier] =
    buildTargets
      .sourceBuildTargets(ipynbPath)
      .map(_.toSeq)
      .getOrElse(buildTargets.allBuildTargetIds)
      .maxByOption(buildTargets.buildTargetsOrder)

  /**
   * `TargetData.addSourceItem` is a plain, synchronous map write with no BSP
   * round-trip; `BuildTargets.scalaTarget`/`targetClasspath` look up a
   * `BuildTargetIdentifier` across every registered `TargetData`, not just
   * whichever one added it, so pointing a notebook's synthetic cell paths at
   * an already-imported real target here is enough for
   * `Compilers.loadCompiler` to pick up its real classpath unmodified.
   * `combinedMarkerPath` is registered too, since `triggerDiagnostics` uses
   * that path for its own, separate `compilers().didChange` call.
   */
  private def reregisterTargetData(ipynbPath: AbsolutePath): Unit = {
    registeredData.remove(ipynbPath).foreach(buildTargets.removeData)
    for {
      target <- bestTarget(ipynbPath)
      if notebooks.contains(ipynbPath)
    } {
      val data = new TargetData
      scalaCellPaths(ipynbPath).foreach(data.addSourceItem(_, target))
      data.addSourceItem(combinedMarkerPath(ipynbPath), target)
      buildTargets.addData(data)
      registeredData.put(ipynbPath, data)
    }
  }

  /**
   * `notebooks` keeps the notebook's full cell order (any kind/language) so
   * `applyStructureChange` can patch it with the client's own array indices;
   * everything downstream (combining/typechecking/target registration) only
   * ever wants the Scala code cells within that order.
   */
  private def scalaCellPaths(ipynbPath: AbsolutePath): Vector[AbsolutePath] =
    notebooks.getOrElse(ipynbPath, Vector.empty).filter(cells.contains)

  private def cellUri(path: AbsolutePath): String =
    cells.get(path).map(_.uri).getOrElse(path.toURI.toString)

  /**
   * Consulted from [[SourceMapper.pcMapping]]. `None` for any path that
   * isn't a currently-known notebook cell (in particular: every ordinary,
   * non-notebook path), in which case the caller falls back to treating
   * `path` as a standalone file.
   */
  def combinedAdjustments(
      path: AbsolutePath
  ): Option[(Input.VirtualFile, Position => Position, AdjustLspData)] = for {
    ref <- cells.get(path)
    cellPaths = scalaCellPaths(ref.ipynbPath)
    idx = cellPaths.indexOf(path)
    if idx >= 0
    combined = combine(ref.ipynbPath, cellPaths)
    startLine = combined.offsets(idx)
  } yield (
    Input.VirtualFile(path.toURI.toString, combined.text),
    (pos: Position) => new Position(pos.getLine + startLine, pos.getCharacter),
    new NotebookAdjustLspData(
      cellPaths.map(cellUri),
      combined.offsets,
      startLine,
    ),
  )

  private def registerCells(
      ipynbPath: AbsolutePath,
      order: Vector[String],
      scalaUris: Set[String],
      initialText: Map[String, String],
  ): Vector[AbsolutePath] = {
    val opened = Vector.newBuilder[AbsolutePath]
    val paths = order.map { uri =>
      val path = uriToPath(uri)
      if (scalaUris.contains(uri)) {
        cells.put(path, CellRef(ipynbPath, uri))
        initialText.get(uri).foreach { text =>
          buffers.put(path, text)
          opened += path
        }
      }
      path
    }
    notebooks.put(ipynbPath, paths)
    opened.result()
  }

  private def applyStructureChange(
      ipynbPath: AbsolutePath,
      structure: NotebookDocumentChangeEventCellStructure,
  ): CellChanges = {
    val current = notebooks.getOrElse(ipynbPath, Vector.empty)
    // `arrayChange.getStart`/`getDeleteCount` index into the notebook's full
    // cell array (every kind/language), not just the Scala cells we track in
    // `cells`, so `current`/`updated`/`replacement` here all have to keep
    // every cell too, or a mixed-language notebook would patch the wrong
    // position. Which of `replacement`'s cells are actually Scala is decided
    // separately below, from `structure.getDidOpen`/`getDidClose`.
    val updated = structure.getArray match {
      case null => current
      case arrayChange =>
        val replacement = Option(arrayChange.getCells)
          .map(
            _.asScala.iterator.map(cell => uriToPath(cell.getDocument)).toVector
          )
          .getOrElse(Vector.empty)
        current.patch(
          arrayChange.getStart,
          replacement,
          arrayChange.getDeleteCount,
        )
    }
    val opened = for {
      opened <- Option(structure.getDidOpen).toVector
      textDocument <- opened.asScala
      if textDocument.getLanguageId == "scala"
      path = uriToPath(textDocument.getUri)
    } yield {
      cells.put(path, CellRef(ipynbPath, textDocument.getUri))
      buffers.put(path, textDocument.getText)
      path
    }
    val closed = for {
      closed <- Option(structure.getDidClose).toVector
      textDocument <- closed.asScala
      path = uriToPath(textDocument.getUri)
    } yield {
      cells.remove(path)
      buffers.remove(path)
      compilers().didClose(path)
      clearDiagnostics(textDocument.getUri)
      path
    }
    notebooks.put(ipynbPath, updated)
    combinedCache.remove(ipynbPath)
    reregisterTargetData(ipynbPath)
    CellChanges(opened, closed)
  }

  /**
   * Drops all state for `ipynbPath` and returns the cell paths (plus its
   * combined-script marker path) whose parser/compiler state the caller
   * must now close, since `MetalsLspService.didClose` never sees these
   * synthetic paths (notebook cells skip `textDocument/didClose` entirely).
   */
  private def forgetNotebook(ipynbPath: AbsolutePath): Vector[AbsolutePath] = {
    val closed = for {
      paths <- notebooks.remove(ipynbPath).toVector
      path <- paths
      ref <- cells.remove(path).toVector
    } yield {
      clearDiagnostics(ref.uri)
      buffers.remove(path)
      compilers().didClose(path)
      path
    }
    val markerPath = combinedMarkerPath(ipynbPath)
    compilers().didClose(markerPath)
    combinedCache.remove(ipynbPath)
    registeredData.remove(ipynbPath).foreach(buildTargets.removeData)
    closed :+ markerPath
  }

  private def clearDiagnostics(uri: String): Unit =
    languageClient.publishDiagnostics(
      new PublishDiagnosticsParams(uri, new ju.ArrayList())
    )

  private def combine(
      ipynbPath: AbsolutePath,
      cellPaths: Vector[AbsolutePath],
  ): CombinedScript =
    combinedCache.getOrElseUpdate(ipynbPath, computeCombined(cellPaths))

  /**
   * A bare expression statement (`xs`, `xs.length`, `println(xs)`, ...) —
   * completely ordinary notebook cell content — is not itself valid at the
   * true top level of a Scala 3 source file: Scala 3 allows multiple
   * top-level `val`/`def`/`class`/... *definitions* side by side, but a
   * standalone expression is only legal as a *statement inside a body*
   * (e.g. a constructor/object body), same as it would be in a REPL or a
   * worksheet. Real worksheets get that leniency from mdoc's wrapping, which
   * we deliberately don't run (that's execution). Wrapping the concatenation
   * in a throwaway `object` gives the same leniency for free, with no
   * execution: every cell becomes a statement in that object's body, and
   * every position shift below only has to account for the one extra header
   * line.
   */
  private val wrapperHeader = "object Notebook {\n"
  private val wrapperFooter = "\n}\n"

  private def computeCombined(
      cellPaths: Vector[AbsolutePath]
  ): CombinedScript = {
    var line = wrapperHeader.count(_ == '\n')
    val offsets = Vector.newBuilder[Int]
    val sb = new StringBuilder(wrapperHeader)
    for {
      path <- cellPaths
      text = buffers.get(path).getOrElse("")
    } {
      offsets += line
      sb.append(text).append('\n')
      line += text.count(_ == '\n') + 1
    }
    sb.append(wrapperFooter)
    CombinedScript(sb.toString, offsets.result())
  }

  /**
   * Typechecks the whole notebook as one concatenated script and republishes
   * the resulting diagnostics against each individual cell's own uri.
   *
   * This intentionally does not go through [[combinedAdjustments]]/
   * `Compilers.didChange`'s own notebook awareness: `markerPath` is never
   * registered as a cell, so `Compilers.didChange` typechecks exactly the
   * `content` passed here with no further remapping, and this method does
   * its own per-cell split of the resulting diagnostics.
   */
  private def triggerDiagnostics(ipynbPath: AbsolutePath): Unit = for {
    _ <- notebooks.get(ipynbPath)
    cellPaths = scalaCellPaths(ipynbPath)
    combined = combine(ipynbPath, cellPaths)
    markerPath = combinedMarkerPath(ipynbPath)
  } {
    compilers()
      .didChange(
        markerPath,
        shouldReturnDiagnostics = true,
        Some(combined.text),
      )
      .map(publishPerCell(cellPaths, combined.offsets, _))
      .recover { case NonFatal(e) =>
        scribe.error(
          s"failed to compute diagnostics for notebook $ipynbPath",
          e,
        )
      }
  }

  private def publishPerCell(
      cellPaths: Vector[AbsolutePath],
      offsets: Vector[Int],
      diagnostics: List[Diagnostic],
  ): Unit = {
    val byCell =
      Vector.fill(cellPaths.length)(new ju.ArrayList[Diagnostic]())
    // Guard against any single malformed diagnostic (e.g. a parse error with
    // no range) losing the whole batch: every other cell still deserves its
    // diagnostics/its "all clear".
    for {
      diagnostic <- diagnostics
      range <- Option(diagnostic.getRange)
      idx = ownerIndex(offsets, range.getStart.getLine)
    } {
      range.getStart.setLine(
        math.max(0, range.getStart.getLine - offsets(idx))
      )
      range.getEnd.setLine(math.max(0, range.getEnd.getLine - offsets(idx)))
      byCell(idx).add(diagnostic)
    }

    for (i <- cellPaths.indices)
      languageClient.publishDiagnostics(
        new PublishDiagnosticsParams(cellUri(cellPaths(i)), byCell(i))
      )
  }

  @tailrec
  private def ownerIndex(offsets: Vector[Int], line: Int, idx: Int = 0): Int =
    if (idx + 1 < offsets.length && offsets(idx + 1) <= line)
      ownerIndex(offsets, line, idx + 1)
    else idx
}

object NotebookProvider {
  val scheme: String = "vscode-notebook-cell"

  def isNotebookCellUri(uri: String): Boolean = uri.startsWith(s"$scheme:")

  /**
   * Deterministic, registry-free mapping from a `vscode-notebook-cell:` uri
   * to a synthetic `AbsolutePath`. Used from
   * `MetalsEnrichments.XtensionString.toAbsolutePath` so it works for any
   * caller, not just ones with a live `NotebookProvider` instance.
   */
  def uriToPath(uri: String): AbsolutePath = {
    val parsed = new URI(uri)
    // Re-wrap the decoded path as a `file:` URI rather than
    // `Paths.get(String)`: on Windows, `parsed.getPath` is a drive-rooted
    // path (`/C:/Users/...`) that `Paths.get(URI)` resolves correctly but
    // `Paths.get(String)` does not.
    val ipynbPath = AbsolutePath(
      Paths.get(new URI("file", null, parsed.getPath, null))
    )
    val fragment = Option(parsed.getRawFragment).getOrElse("")
    cellPath(ipynbPath, fragment)
  }

  def cellPath(ipynbPath: AbsolutePath, cellFragment: String): AbsolutePath = {
    val sanitized = cellFragment.replaceAll("[^A-Za-z0-9_-]", "_")
    notebookScratchDir(ipynbPath).resolve(
      s"${if (sanitized.isEmpty) "cell" else sanitized}.sc"
    )
  }

  def combinedMarkerPath(ipynbPath: AbsolutePath): AbsolutePath =
    notebookScratchDir(ipynbPath).resolve("__combined__.sc")

  private def notebookScratchDir(ipynbPath: AbsolutePath): AbsolutePath = {
    val name = ipynbPath.filename
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    ipynbPath.parent
      .resolve(Directories.tmp)
      .resolve("notebooks")
      .resolve(stem)
  }

  private final case class CellRef(ipynbPath: AbsolutePath, uri: String)
  private final case class CombinedScript(text: String, offsets: Vector[Int])

  /**
   * The cell paths a [[didOpen]]/[[didChange]]/[[didClose]] call newly
   * `opened` or `closed`, for the caller to feed to its own parsed-trees
   * cache (`opened` needs `parseTrees`, `closed` needs `trees.didClose`).
   */
  final case class CellChanges(
      opened: Vector[AbsolutePath],
      closed: Vector[AbsolutePath],
  )
  object CellChanges {
    val empty: CellChanges = CellChanges(Vector.empty, Vector.empty)
  }
}
