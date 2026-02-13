package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.jpc.JavacDiagnostic
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

sealed abstract class ImportMissingSymbol(
    compilers: Compilers,
    buildTargets: BuildTargets,
) extends CodeAction {

  protected def allSymbolsTitle: String

  protected def isScalaOrSbt(file: AbsolutePath): Boolean =
    Seq("scala", "sbt", "sc").contains(file.extension)

  /**
   * Filter the import actions based on specific implementation rules.
   * This can be overridden by subclasses to implement different filtering strategies.
   */
  protected def filterImportActions(
      allActions: Seq[l.CodeAction]
  ): Seq[l.CodeAction] = {
    // Default implementation returns all actions
    allActions
  }

  protected def isImportAllSourceAction: Boolean
  protected def getDiagnostics(params: l.CodeActionParams): Seq[l.Diagnostic]

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val uri = params.getTextDocument().getUri()
    val file = uri.toAbsolutePath
    lazy val isScala3 =
      buildTargets.scalaVersion(file).exists(ScalaVersions.isScala3Version)

    def getChanges(codeAction: l.CodeAction): IterableOnce[l.TextEdit] =
      codeAction
        .getEdit()
        .getChanges()
        .asScala
        .values
        .flatMap(_.asScala)

    def joinActionEdits(actions: Seq[l.CodeAction]) = {
      actions
        .flatMap(getChanges)
        .distinct
        .groupBy(_.getRange())
        .values
        .map(_.sortBy(_.getNewText()).reduceLeft { (l, r) =>
          l.setNewText((l.getNewText() + r.getNewText()).replace("\n\n", "\n"))
          l
        })
        .toSeq
    }

    def joinActions(actions: Seq[l.CodeAction]) = {
      val mainAction = actions.head
      val allDiagnostics =
        actions.flatMap(_.getDiagnostics().asScala).asJava
      mainAction.setDiagnostics(allDiagnostics)
      val edits = joinActionEdits(actions.toSeq)
      mainAction.setEdit(
        new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava)
      )
      mainAction
    }

    def importMissingSymbol(
        diagnostic: l.Diagnostic,
        name: String,
        findExtensionMethods: Boolean,
    ): Future[Seq[l.CodeAction]] = {
      val offset =
        if (isScala3) diagnostic.getRange().getEnd()
        else diagnostic.getRange().getStart()

      val textDocumentPositionParams = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        offset,
      )
      compilers
        .autoImports(
          textDocumentPositionParams,
          name,
          findExtensionMethods,
          token,
        )
        .map { imports =>
          imports.asScala.map { i =>
            val uri = params.getTextDocument().getUri()
            val edit = List(uri.toAbsolutePath -> i.edits.asScala.toSeq)

            CodeActionBuilder.build(
              title = ImportMissingSymbol.title(name, i.packageName),
              kind = this.kind,
              diagnostics = List(diagnostic),
              changes = edit,
            )
          }.toSeq
        }
    }

    def importMissingSymbols(
        codeActions: Seq[l.CodeAction]
    ): Seq[l.CodeAction] = {
      val uniqueCodeActions = codeActions
        .groupBy(_.getDiagnostics())
        .values
        .filter(_.length == 1)
        .flatten
        .toList

      if (codeActions.length > 1 && uniqueCodeActions.length > 0) {
        val diags = uniqueCodeActions.flatMap(_.getDiagnostics().asScala)
        val edits = joinActionEdits(uniqueCodeActions)

        val allSymbols: l.CodeAction =
          CodeActionBuilder.build(
            title = this.allSymbolsTitle,
            kind = this.kind,
            diagnostics = diags,
            changes = List(uri.toAbsolutePath -> edits),
          )

        allSymbols +: codeActions
      } else {
        codeActions
      }
    }

    val MissingSymbol = new MissingSymbolDiagnostic(isScala3, file)

    Future
      .sequence(
        getDiagnostics(params)
          .collect {
            case d @ MissingSymbol(name, findExtensionMethods)
                if this.isImportAllSourceAction || params
                  .getRange()
                  .overlapsWith(d.getRange()) =>
              importMissingSymbol(d, name, findExtensionMethods)
          }
      )
      .map { actions =>
        val groupedByImported = actions
          .filter(_.nonEmpty)
          .groupBy(
            _.head.getDiagnostics().asScala.headOption.collect {
              case ScalacDiagnostic.SymbolNotFound(name) => name
            }
          )
        val deduplicated = groupedByImported.flatMap {
          case (None, actions) =>
            actions
          case (_, actions) =>
            if (actions.length > 1) {

              /**
               * If based on all possible imports, we try to minimize the possible
               * imports that are needed to fix all the diagnostics.
               *
               * In same places such as Future.successful, we know that only Scala Future fits
               * and the Java one doesn't have this static method. So we can filter out the Java one.
               *
               * Even if there is another place that is ok with using the Java Future,
               * since importing the Java one would break one of the imports, we don't
               * suggest it.
               */
              val minimalImportsSet =
                actions.tail.foldLeft(actions.head.map(_.getTitle()).toSet) {
                  case (actions, action) =>
                    actions.intersect(action.map(_.getTitle()).toSet)
                }

              val minimalActions =
                actions.flatten.filter(a => minimalImportsSet(a.getTitle()))
              val joined = minimalActions
                .groupBy(_.getTitle())
                .collect { case (_, actions) =>
                  joinActions(actions.toSeq)
                }
              List(joined)

            } else {
              actions
            }
        }.flatten

        // Generate the "Import all" action if applicable
        val allActions = importMissingSymbols(deduplicated.toSeq.sorted)

        // Apply the filtering strategy from the implementation class
        val filteredActions =
          filterImportActions(allActions)

        filteredActions
      }
  }
}

object ImportMissingSymbol {
  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  def allSymbolsTitle: String =
    s"Import all missing symbols that are unambiguous"
}

/**
 * The QuickFix implementation of ImportMissingSymbol, which is used
 * when handling missing imports one at a time.
 */
class ImportMissingSymbolQuickFix(
    compilers: Compilers,
    buildTargets: BuildTargets,
) extends ImportMissingSymbol(compilers, buildTargets) {

  override protected def getDiagnostics(
      params: l.CodeActionParams
  ): Seq[l.Diagnostic] =
    params.getContext().getDiagnostics().asScala.toSeq

  override val kind: String = ImportMissingSymbolQuickFix.kind
  override protected val allSymbolsTitle: String =
    ImportMissingSymbol.allSymbolsTitle
  override protected def isImportAllSourceAction: Boolean = false
}

object ImportMissingSymbolQuickFix {
  final val kind: String = l.CodeActionKind.QuickFix
}

/**
 * The "source" implementation of ImportMissingSymbol, which is used
 * for automatically importing all unambiguous symbols for the entire file.
 *
 * This implementation only imports symbols that have exactly one unambiguous import source.
 * Any symbols with multiple possible imports (like Future from scala.concurrent and
 * java.util.concurrent) will be skipped and require manual resolution.
 */
class SourceAddMissingImports(
    compilers: Compilers,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
) extends ImportMissingSymbol(compilers, buildTargets) {

  override protected def getDiagnostics(
      params: l.CodeActionParams
  ): Seq[l.Diagnostic] =
    params.getContext().getDiagnostics().asScala.toSeq

  override val kind: String = SourceAddMissingImports.kind
  override protected val allSymbolsTitle: String = SourceAddMissingImports.title
  override protected def isImportAllSourceAction: Boolean = true

  /**
   * Override the filtering method to only keep unambiguous imports.
   * This ensures we only auto-import symbols with exactly one available import.
   */
  override protected def filterImportActions(
      allActions: Seq[l.CodeAction]
  ): Seq[l.CodeAction] = {
    if (allActions.length > 1) {
      allActions.find(a => a.getTitle == SourceAddMissingImports.title).toSeq
    } else {
      allActions.foreach(a => a.setTitle(SourceAddMissingImports.title))
      allActions
    }
  }
}

object SourceAddMissingImports {
  final val kind: String = l.CodeActionKind.Source + ".addMissingImports"
  final val title: String =
    "Add all missing imports that are unambiguous for the entire file"
}

class MissingSymbolDiagnostic(
    isScala3: Boolean,
    file: Option[AbsolutePath] = None,
) {
  def this(isScala3: Boolean) = this(isScala3, None)
  def this(isScala3: Boolean, file: AbsolutePath) = this(isScala3, Some(file))

  def unapply(d: l.Diagnostic): Option[(String, Boolean)] =
    d match {
      case ScalacDiagnostic.SymbolNotFound(name) =>
        Some(name, false)
      // `foo.xxx` where `xxx` is not a member of `foo`
      // we search for `xxx` only when the target is Scala3
      // considering there might be an extension method.
      case ScalacDiagnostic.NotAMember(name) if isScala3 =>
        Some(name, true)
      case JavacDiagnostic.CannotFindSymbol(err)
          if file.exists(_.toLanguage.isJava) && err.isCantResolve =>
        Some(err.symbol, false)
      case _ => None
    }
}
