package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Paths
import java.util as ju
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.io.AbsolutePath
import ch.epfl.scala.bsp4j as b
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeNotebookDocumentParams
import org.eclipse.lsp4j.DidCloseNotebookDocumentParams
import org.eclipse.lsp4j.DidOpenNotebookDocumentParams
import org.eclipse.lsp4j.DidSaveNotebookDocumentParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.NotebookCell
import org.eclipse.lsp4j.NotebookCellKind
import org.eclipse.lsp4j.NotebookDocumentChangeEventCellStructure
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextEdit

import scala.util.chaining.scalaUtilChainingOps

/**
 * Gives Scala notebook cells (`vscode-notebook-cell:` documents, see
 * https://github.com/scalameta/metals-feature-requests/issues/236) cross-cell
 * language support without running any code: [[combinedAdjustments]]
 * concatenates a notebook's cells into one virtual script so a cell can see
 * imports/vals from earlier ones, and [[triggerDiagnostics]] typechecks that
 * concatenation and fans the resulting diagnostics back out per cell.
 *
 * Each cell gets a synthetic, never-written-to-disk `.sc` path (see
 * [[NotebookProvider.cellPath]]) that is a pure function of its uri, so
 * `toAbsolutePath` can resolve it with no registry lookup.
 *
 * Deliberately out of scope: executing cells, and any project/build-target
 * classpath inside a cell (same non-goals as the design doc at
 * https://github.com/scalameta/metals/issues/4434) — cells only see the
 * standard library, exactly like any other standalone scratch `.sc` file.
 */
final class NotebookProvider(
    buffers: Buffers,
    languageClient: MetalsLanguageClient,
    compilers: () => Compilers,
    // Ordinary `textDocument/didOpen|didChange` also feed the parsed-trees
    // cache that features like `textDocument/foldingRange` read from
    // (`MetalsLspService.parseTrees`, a `BatchedFunction`); since notebook
    // cells skip that normal flow entirely (MetalsLspService.didOpen/didChange
    // are no-ops for `vscode-notebook-cell:` uris), we have to feed it
    // ourselves or those features see a cell that was "never opened".
    parseTrees: AbsolutePath => Future[Unit],
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext) {
  import NotebookProvider.*

  // real `.ipynb` path -> ordered synthetic cell paths
  private val notebooks = TrieMap.empty[AbsolutePath, Vector[AbsolutePath]]
  // synthetic cell path -> which notebook/uri it belongs to
  private val cells = TrieMap.empty[AbsolutePath, CellRef]
  // real `.ipynb` path -> its last-combined script + cell offsets; combining
  // is the same string-building work whether it's for one hover/completion
  // request or the whole-notebook diagnostics pass, so cache it rather than
  // rebuilding it from scratch on every single request. Invalidated whenever
  // any cell's content or the cell list itself changes.
  private val combinedCache =
    TrieMap.empty[AbsolutePath, CombinedScript]
  // real `.ipynb` path -> the build target its cells should resolve against,
  // if the user has associated one (absent until they pick one, or if the
  // notebook has never been opened while the workspace had exactly one
  // build target to auto-associate with).
  private val associatedTarget =
    TrieMap.empty[AbsolutePath, b.BuildTargetIdentifier]
  // real `.ipynb` path -> the `TargetData` currently registered into
  // `buildTargets` for that notebook's *current* cell paths. `TargetData`
  // has no "remove a single source item" operation, only whole-instance
  // granularity, so a change to the cell set or the association replaces
  // this wholesale rather than mutating it in place.
  private val registeredData = TrieMap.empty[AbsolutePath, TargetData]

  def didOpen(params: DidOpenNotebookDocumentParams): Unit = {
    val ipynbPath = params.getNotebookDocument.getUri.toAbsolutePath
    val languageById =
      params.getCellTextDocuments.asScala.toMapBy(_.getUri, _.getLanguageId)
    val order = params.getNotebookDocument.getCells.asScala.iterator
      .filter(cell => cell.getKind == NotebookCellKind.Code)
      .filter(cell => languageById.get(cell.getDocument) contains "scala")
      .map(_.getDocument)
      .toVector
    val initialText =
      params.getCellTextDocuments.asScala.toMapBy(_.getUri, _.getText)
    registerCells(ipynbPath, order, initialText)
    if (!associatedTarget.contains(ipynbPath)) {
      buildTargets.allBuildTargetIds match {
        case Seq(only) => associate(ipynbPath, Some(only))
        case _ => // ambiguous or none; leave unassociated until the user picks
      }
    }
    triggerDiagnostics(ipynbPath)
  }

  def didChange(params: DidChangeNotebookDocumentParams): Unit = {
    val ipynbPath = params.getNotebookDocument.getUri.toAbsolutePath
    val cellsChange = Option(params.getChange).flatMap(c => Option(c.getCells))

    for {
      cellsChange <- cellsChange
      structure <- Option(cellsChange.getStructure)
    } applyStructureChange(ipynbPath, structure)

    for {
      cellsChange <- cellsChange
      textContents <- Option(cellsChange.getTextContent)
      textContent <- textContents.asScala
    } {
      val path = uriToPath(textContent.getDocument.getUri)
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
      parseTrees(path)
      combinedCache.remove(ipynbPath)
    }

    triggerDiagnostics(ipynbPath)
  }

  def didClose(params: DidCloseNotebookDocumentParams): Unit = {
    val ipynbPath = params.getNotebookDocument.getUri.toAbsolutePath
    forgetNotebook(ipynbPath)
  }

  def didSave(@annotation.unused params: DidSaveNotebookDocumentParams): Unit =
    ()

  /**
   * Associates `ipynbPath` with one of the workspace's build targets, so its
   * cells' presentation-compiler features see that target's real dependency
   * classpath instead of just the standard library. Auto-picks if there's
   * exactly one candidate, does nothing if there are none, otherwise asks
   * the user via `metals/quickPick` — mirrors
   * `DebugDiscovery.requestMain`'s auto-pick/quickpick split.
   */
  def chooseAndSetBuildTarget(
      ipynbPath: AbsolutePath
  ): Future[Option[b.BuildTargetIdentifier]] =
    buildTargets.allBuildTargetIds match {
      case Seq(only) =>
        setBuildTarget(ipynbPath, Some(only))
        Future.successful(Some(only))
      case Seq() =>
        Future.successful(None)
      case many =>
        val items = many.map { id =>
          new MetalsQuickPickItem(
            id.getUri,
            buildTargets.info(id).map(_.getDisplayName).getOrElse(id.getUri),
          )
        }
        languageClient
          .metalsQuickPick(
            new MetalsQuickPickParams(
              items.asJava,
              placeHolder = "Pick a build target for this notebook's cells",
            )
          )
          .asScala
          .map(_.flatMap(choice => many.find(_.getUri == choice.itemId)))
          .map { chosen =>
            chosen.foreach(id => setBuildTarget(ipynbPath, Some(id)))
            chosen
          }
    }

  def setBuildTarget(
      ipynbPath: AbsolutePath,
      target: Option[b.BuildTargetIdentifier],
  ): Unit = {
    associate(ipynbPath, target)
    triggerDiagnostics(ipynbPath)
  }

  private def associate(
      ipynbPath: AbsolutePath,
      target: Option[b.BuildTargetIdentifier],
  ): Unit = {
    target match {
      case Some(id) => associatedTarget.put(ipynbPath, id)
      case None => associatedTarget.remove(ipynbPath)
    }
    reregisterTargetData(ipynbPath)
  }

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
      target <- associatedTarget.get(ipynbPath)
      cellPaths <- notebooks.get(ipynbPath)
    } {
      val data = new TargetData
      cellPaths.foreach(data.addSourceItem(_, target))
      data.addSourceItem(combinedMarkerPath(ipynbPath), target)
      buildTargets.addData(data)
      registeredData.put(ipynbPath, data)
    }
  }

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
    cellPaths <- notebooks.get(ref.ipynbPath)
    idx = cellPaths.indexOf(path)
    if idx >= 0
    combined = combine(ref.ipynbPath, cellPaths)
    startLine = combined.offsets(idx)
  } yield (
    Input.VirtualFile(path.toURI.toString, combined.text),
    (pos: Position) => new Position(pos.getLine + startLine, pos.getCharacter),
    cellAdjustLspData(cellPaths, combined.offsets, startLine),
  )

  /**
   * Hover/completion/diagnostic/textEdit ranges are always local to the
   * requesting cell (they're anchored at the position we asked about), so
   * the single fixed `startLine` shift from [[AdjustLspData.adjustPos]] is
   * correct for them. `Location`-returning features (definition, references,
   * ...) are different: they can point anywhere in the concatenated script,
   * so each one gets its own owning cell's offset and uri instead.
   */
  private def cellAdjustLspData(
      cellPaths: Vector[AbsolutePath],
      offsets: Vector[Int],
      startLine: Int,
  ): AdjustLspData = new AdjustLspData {
    override def adjustPos(
        pos: Position,
        adjustToZero: Boolean = true,
    ): Position = new Position(pos.getLine - startLine, pos.getCharacter).tap {
      adjusted =>
        if (adjustToZero) {
          if (adjusted.getCharacter < 0) adjusted.setCharacter(0)
          if (adjusted.getLine < 0) adjusted.setLine(0)
        }
    }

    override def adjustLocation(location: Location): Location = {
      val idx = ownerIndex(offsets, location.getRange.getStart.getLine)
      val cellOffset = offsets(idx)
      val uri = cells
        .get(cellPaths(idx))
        .map(_.uri)
        .getOrElse(cellPaths(idx).toURI.toString)

      def shift(pos: Position): Unit =
        pos.setLine(math.max(0, pos.getLine - cellOffset))

      shift(location.getRange.getStart)
      shift(location.getRange.getEnd)
      location.setUri(uri)
      location
    }

    override def adjustLocations(
        locations: ju.List[Location]
    ): ju.List[Location] =
      locations.map(adjustLocation)
  }

  private def registerCells(
      ipynbPath: AbsolutePath,
      order: Vector[String],
      initialText: Map[String, String],
  ): Unit = {
    forgetNotebook(ipynbPath)
    val paths = order.map { uri =>
      val path = uriToPath(uri)
      cells.put(path, CellRef(ipynbPath, uri))
      initialText.get(uri).foreach { text =>
        buffers.put(path, text)
        parseTrees(path)
      }
      path
    }
    notebooks.put(ipynbPath, paths)
  }

  private def applyStructureChange(
      ipynbPath: AbsolutePath,
      structure: NotebookDocumentChangeEventCellStructure,
  ): Unit = {
    val current = notebooks.getOrElse(ipynbPath, Vector.empty)
    // Language for a cell newly appearing in this change comes from its
    // matching `didOpen` entry; a cell reappearing without one is just being
    // reordered/moved, so trust whatever we already recorded for it.
    val openedLanguageById = Option(structure.getDidOpen)
      .map(_.asScala.toMapBy(_.getUri, _.getLanguageId))
      .getOrElse(Map.empty)
    def isScalaCodeCell(cell: NotebookCell): Boolean =
      cell.getKind == NotebookCellKind.Code && {
        openedLanguageById.get(cell.getDocument) match {
          case Some(languageId) => languageId == "scala"
          case None => cells.contains(uriToPath(cell.getDocument))
        }
      }
    val updated = structure.getArray match {
      case null => current
      case arrayChange =>
        val replacement = Option(arrayChange.getCells)
          .map(
            _.asScala.iterator
              .filter(isScalaCodeCell)
              .map(cell => uriToPath(cell.getDocument))
              .toVector
          )
          .getOrElse(Vector.empty)
        current.patch(
          arrayChange.getStart,
          replacement,
          arrayChange.getDeleteCount,
        )
    }
    for {
      opened <- Option(structure.getDidOpen)
      textDocument <- opened.asScala
      if textDocument.getLanguageId == "scala"
      path = uriToPath(textDocument.getUri)
    } {
      cells.put(path, CellRef(ipynbPath, textDocument.getUri))
      buffers.put(path, textDocument.getText)
      parseTrees(path)
    }
    for {
      closed <- Option(structure.getDidClose)
      textDocument <- closed.asScala
      path = uriToPath(textDocument.getUri)
    } {
      cells.remove(path)
      buffers.remove(path)
      clearDiagnostics(textDocument.getUri)
    }
    notebooks.put(ipynbPath, updated)
    combinedCache.remove(ipynbPath)
    reregisterTargetData(ipynbPath)
  }

  private def forgetNotebook(ipynbPath: AbsolutePath): Unit = {
    for {
      paths <- notebooks.remove(ipynbPath)
      path <- paths
      ref <- cells.remove(path)
    } {
      clearDiagnostics(ref.uri)
      buffers.remove(path)
    }
    combinedCache.remove(ipynbPath)
    associatedTarget.remove(ipynbPath)
    registeredData.remove(ipynbPath).foreach(buildTargets.removeData)
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
    cellPaths <- notebooks.get(ipynbPath)
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

    for {
      i <- cellPaths.indices
      uri = cells
        .get(cellPaths(i))
        .map(_.uri)
        .getOrElse(cellPaths(i).toURI.toString)
    } languageClient.publishDiagnostics(
      new PublishDiagnosticsParams(uri, byCell(i))
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
    val ipynbPath = AbsolutePath(Paths.get(parsed.getPath))
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
}
