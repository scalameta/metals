package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Import
import scala.meta.Importee
import scala.meta.Importer
import scala.meta.Tree
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

// Cuts out invalid subsets of or whole imports
sealed abstract class RemoveInvalidImport(
    trees: Trees,
    buildTargets: BuildTargets,
) extends CodeAction {
  protected def allImportsTitle: String

  protected def isRemoveAllSourceAction: Boolean

  protected def getDiagnostics(params: l.CodeActionParams): Seq[l.Diagnostic]

  protected def getRange(params: l.CodeActionParams): l.Range

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {
    val file = params.getTextDocument().getUri().toAbsolutePath
    val range = getRange(params)
    lazy val isScala3 =
      buildTargets.scalaVersion(file).exists(ScalaVersions.isScala3Version)

    def relevantDiagRange(diag: l.Diagnostic): Boolean =
      isRemoveAllSourceAction || range.overlapsWith(diag.getRange())

    val sortedActions = getDiagnostics(params)
      .collect {
        case diag @ ScalacDiagnostic.SymbolNotFound(name)
            if relevantDiagRange(diag) =>
          diag.getRange() -> name
        case diag @ ScalacDiagnostic.ObjectNotAMemberOfPackage(name)
            if !isScala3 && relevantDiagRange(diag) =>
          diag.getRange() -> name
        case diag @ ScalacDiagnostic.NotAMember(name)
            if isScala3 && relevantDiagRange(diag) =>
          diag.getRange() -> name
      }
      .sortBy { case (diagRange, _) =>
        diagRange.getStart().getLine() -> diagRange.getStart().getCharacter()
      }

    lazy val imports = trees.findAllInRange[Import](file, range).toList

    val codeActions = if (sortedActions.isEmpty || imports.isEmpty) {
      // Optimization: fast-exit path
      Nil
    } else {
      val importsAndDiags =
        groupDiagnosticsByTree(sortedActions.toList, imports)
      val (caseByCaseRemovals, fullRemovals) = importsAndDiags.map {
        case (imprt, diags) =>
          val (caseByCase, aggregate) = removeInvalidFromImport(imprt, diags)
          caseByCase.map { case (title, edits) =>
            title -> edits.toLspRanges(imprt, true)
          } -> aggregate.toLspRanges(imprt, true)
      }.unzip

      // Actions for removing each bad import individually
      val removeSingleImportActions = caseByCaseRemovals.flatten.map {
        case (title, removalRanges) =>
          CodeActionBuilder.build(
            title = title,
            kind = this.kind,
            changes = List(file -> removalRanges.map(new l.TextEdit(_, ""))),
          )
      }

      // Action (possibly empty) for removing all bad imports at once
      val removeAllImportsAction = if (removeSingleImportActions.lengthIs > 0) {
        Seq(
          CodeActionBuilder.build(
            title = allImportsTitle,
            kind = this.kind,
            changes =
              List(file -> fullRemovals.flatten.map(new l.TextEdit(_, ""))),
          )
        )
      } else {
        Nil
      }

      if (isRemoveAllSourceAction) removeAllImportsAction
      else if (removeSingleImportActions.lengthIs == 1)
        removeSingleImportActions
      else removeAllImportsAction ++ removeSingleImportActions
    }
    Future.successful(codeActions)
  }

  private sealed trait RemovalEdit {

    /**
     * Turn the removal edit into a concrete set of ranges to remove
     *
     * @param tree for which AST node is this removal?
     * @param trimTrailingNewline trim trailing whitespace if tree is fully removed
     * @return ranges to remove
     */
    def toLspRanges(
        tree: Tree,
        trimTrailingNewline: Boolean,
    ): Seq[l.Range] = {
      this match {
        case RemovalEdit.EntireTree =>
          val pos = tree.pos
          val range = pos.toLsp

          // If there is a newline right after the tree, include that in the range
          if (
            trimTrailingNewline && pos.input.chars.lift(pos.end).contains('\n')
          ) {
            val end = range.getEnd()
            range.setEnd(new l.Position(end.getLine() + 1, 0))
          }
          Seq(range)
        case RemovalEdit.Parts(parts) =>
          parts
      }
    }
  }
  private object RemovalEdit {
    case object EntireTree extends RemovalEdit
    case class Parts(parts: Seq[l.Range]) extends RemovalEdit

    val Empty: Parts = Parts(Seq.empty)

    /**
     * Given a sequence of elements and the removals applied to each of them, build up the removal
     * for the entire sequence.
     *
     * This handles a couple details
     *
     *   - dropping ranges _between_ elements when at least one element was removed.
     *     Ex: removing `bar` from `{foo, bar, baz}` should be `{foo, baz}`, not `{foo, , baz}`
     *
     *   - dropping the entire range if every component was dropped
     *     Ex: removing `foo` and `bar` from `{foo, baz}` should drop the entire seq
     *
     * @param orderedElements ordered elements of the seq and the removal edit to them
     * @return removal edit for the seq as a whole
     */
    def fromSeqEdits(
        orderedElements: Iterator[(Tree, RemovalEdit)]
    ): RemovalEdit = {

      var seqIsFullyRemoved = true
      val removalRanges = Seq.newBuilder[l.Range]
      def addRemovalRanges(ranges: Seq[l.Range]) =
        for (range <- ranges; if !range.isOffset && !range.isNone) {
          removalRanges += range
        }

      // If the previous entry was removed, Left(range of pending removal).
      // If the previous entry was present, Right(previous tree)
      var runningRemovalOrPrev: Either[l.Range, Tree] =
        orderedElements.next() match {
          case (firstElement, RemovalEdit.EntireTree) =>
            Left(firstElement.pos.toLsp)
          case (firstElement, RemovalEdit.Parts(parts)) =>
            seqIsFullyRemoved = false
            addRemovalRanges(parts)
            Right(firstElement)
        }

      for ((element, removeEdit) <- orderedElements) {
        (removeEdit, runningRemovalOrPrev) match {
          case (RemovalEdit.EntireTree, Left(runningRemoval)) =>
            val newRunningRemoval =
              new l.Range(runningRemoval.getStart(), element.pos.toLsp.getEnd())
            runningRemovalOrPrev = Left(newRunningRemoval)

          case (RemovalEdit.EntireTree, Right(prevTree)) =>
            val runningRemoval = new l.Range(
              prevTree.pos.toLsp.getEnd(),
              element.pos.toLsp.getEnd(),
            )
            runningRemovalOrPrev = Left(runningRemoval)

          case (RemovalEdit.Parts(parts), Left(runningRemoval)) =>
            addRemovalRanges(Seq(if (seqIsFullyRemoved) {
              new l.Range(runningRemoval.getStart, element.pos.toLsp.getStart())
            } else {
              runningRemoval
            }))
            seqIsFullyRemoved = false
            addRemovalRanges(parts)
            runningRemovalOrPrev = Right(element)

          case (RemovalEdit.Parts(parts), Right(_)) =>
            seqIsFullyRemoved = false
            addRemovalRanges(parts)
            runningRemovalOrPrev = Right(element)
        }
      }

      if (seqIsFullyRemoved) {
        RemovalEdit.EntireTree
      } else {
        runningRemovalOrPrev match {
          case Left(runningRemoval) => addRemovalRanges(Seq(runningRemoval))
          case Right(_) => ()
        }
        RemovalEdit.Parts(removalRanges.result())
      }
    }
  }

  // Possibly remove invalid importee
  private def removeInvalidFromImportee(
      importee: Importee,
      diags: Seq[(l.Range, String)],
  ): Option[(String, RemovalEdit)] = {
    val nameOpt = importee match {
      case Importee.Name(name) => Some(name)
      case Importee.Rename(name, _) => Some(name)
      case Importee.Unimport(name) => Some(name)
      case Importee.Wildcard() | Importee.Given(_) | Importee.GivenAll() => None
    }
    nameOpt.flatMap { name =>
      if (diags.contains(name.pos.toLsp -> name.value)) {
        Some(RemoveInvalidImport.title(name.value) -> RemovalEdit.EntireTree)
      } else {
        None
      }
    }
  }

  // Remove invalid elements from importer
  // Return (all possible individual removals, result of removing everything)
  private def removeInvalidFromImporter(
      importer: Importer,
      diags: List[(l.Range, String)],
  ): (Seq[(String, RemovalEdit)], RemovalEdit) = {

    // Found error in the `ref` (ex: `bar` being invalid in `import foo.bar.baz.Quz`)
    val diagInRef = diags.headOption.filter { case (r, _) =>
      importer.ref.pos.encloses(r)
    }

    diagInRef match {
      case Some((_, invalidName)) =>
        (Seq(
          RemoveInvalidImport.title(invalidName) -> RemovalEdit.EntireTree
        ) -> RemovalEdit.EntireTree)
      case None =>
        val importees = importer.importees
        val importeeEdits = groupDiagnosticsByTree(diags, importees)
          .map { case (importee, diags) =>
            removeInvalidFromImportee(importee, diags)
          }

        val caseByCase = importeeEdits.zipWithIndex
          .collect { case ((Some(removal), idx)) => removal -> idx }
          .map { case ((title, removal), index) =>
            title -> RemovalEdit.fromSeqEdits(
              importees.iterator.zipWithIndex.map { case (tree, treeIndex) =>
                tree -> (if (treeIndex == index) removal
                         else RemovalEdit.Empty)
              }
            )
          }
        val aggregate = RemovalEdit.fromSeqEdits(
          importees.zip(importeeEdits).iterator.map {
            case (importee, editOpt) =>
              importee -> editOpt.fold[RemovalEdit](RemovalEdit.Empty)(_._2)
          }
        )
        caseByCase -> aggregate
    }
  }

  // Remove invalid elements from import
  // Return (all possible individual removals, result of removing everything)
  private def removeInvalidFromImport(
      imprt: Import,
      diags: List[(l.Range, String)],
  ): (Seq[(String, RemovalEdit)], RemovalEdit) = {

    val importers = imprt.importers
    val (caseByCaseImporterEdits, allEdits) =
      groupDiagnosticsByTree(diags, importers).map { case (importer, diags) =>
        removeInvalidFromImporter(importer, diags)
      }.unzip

    val caseByCase = caseByCaseImporterEdits.zipWithIndex
      .flatMap { case (removals, index) =>
        removals.map { case (title, removal) =>
          title -> RemovalEdit.fromSeqEdits(
            importers.iterator.zipWithIndex.map { case (tree, treeIndex) =>
              tree -> (if (treeIndex == index) removal else RemovalEdit.Empty)
            }
          )
        }
      }
    val aggregate = RemovalEdit.fromSeqEdits(
      importers.zip(allEdits).iterator
    )

    caseByCase -> aggregate
  }

  /**
   * Group diags associated with ranges up along the subtrees containing them
   *
   * Pre-condition: diags and trees are both sorted by start of range
   * Post-condition: output list is sorted by trees and diag groups are also sorted
   * Post-condition: add trees are in the output (though some diags may be dropped)
   */
  private def groupDiagnosticsByTree[T <: Tree](
      diags: List[(l.Range, String)],
      trees: List[T],
  ): List[(T, List[(l.Range, String)])] = {
    val diagnosticsByTree = List.newBuilder[(T, List[(l.Range, String)])]
    var remainingDiags = diags

    for (tree <- trees) {
      val treeRange = tree.pos.toLsp
      val (thisTree, newRemainingDiags) = remainingDiags.span {
        case (diagRange, _) =>
          diagRange.getStart() <= treeRange.getEnd
      }
      val diagsForTree = thisTree.filter { case (diagRange, _) =>
        treeRange.encloses(diagRange)
      }
      diagnosticsByTree += tree -> diagsForTree
      remainingDiags = newRemainingDiags
    }

    diagnosticsByTree.result()
  }
}

object RemoveInvalidImport {
  def title(name: String): String =
    s"Remove invalid import of '$name'"
}

class RemoveInvalidImportQuickFix(
    trees: Trees,
    buildTargets: BuildTargets,
) extends RemoveInvalidImport(trees, buildTargets) {

  override protected def getDiagnostics(
      params: l.CodeActionParams
  ): Seq[l.Diagnostic] =
    params.getContext().getDiagnostics().asScala.toSeq

  override protected def getRange(params: l.CodeActionParams): l.Range =
    params.getRange()

  override val kind: String = l.CodeActionKind.QuickFix

  override protected val allImportsTitle: String =
    RemoveInvalidImportQuickFix.allImportsTitle

  override protected def isRemoveAllSourceAction: Boolean = false
}

object RemoveInvalidImportQuickFix {
  val allImportsTitle = "Remove all invalid imports"
}

class SourceRemoveInvalidImports(
    trees: Trees,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
) extends RemoveInvalidImport(trees, buildTargets) {

  override protected def getDiagnostics(
      params: l.CodeActionParams
  ): Seq[l.Diagnostic] = {
    val uri = params.getTextDocument().getUri()
    val file = uri.toAbsolutePath
    this.diagnostics.getFileDiagnostics(file).toSeq
  }

  override protected def getRange(params: l.CodeActionParams): l.Range = {
    val uri = params.getTextDocument().getUri()
    val file = uri.toAbsolutePath
    this.trees.get(file).fold(new l.Range())(_.pos.toLsp)
  }

  override val kind: String = SourceRemoveInvalidImports.kind

  override protected val allImportsTitle: String =
    SourceRemoveInvalidImports.title

  override protected def isRemoveAllSourceAction: Boolean = true
}

object SourceRemoveInvalidImports {
  val title = "Remove all invalid imports for the entire file"

  val kind: String = l.CodeActionKind.Source + ".removeInvalidImports"
}
