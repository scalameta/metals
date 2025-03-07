package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ImportMissingSymbol(compilers: Compilers, buildTargets: BuildTargets)
    extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val uri = params.getTextDocument().getUri()
    val file = uri.toAbsolutePath
    lazy val isScala3 =
      (for {
        buildId <- buildTargets.inverseSources(file)
        target <- buildTargets.scalaTarget(buildId)
        isScala3 = ScalaVersions.isScala3Version(
          target.scalaInfo.getScalaVersion()
        )
      } yield isScala3).getOrElse(false)

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
        findExtensionMethods: Boolean = false,
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
              kind = l.CodeActionKind.QuickFix,
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
            title = ImportMissingSymbol.allSymbolsTitle,
            kind = l.CodeActionKind.QuickFix,
            diagnostics = diags,
            changes = List(uri.toAbsolutePath -> edits),
          )

        allSymbols +: codeActions
      } else {
        codeActions
      }
    }

    Future
      .sequence(
        params
          .getContext()
          .getDiagnostics()
          .asScala
          .collect {
            case diag @ ScalacDiagnostic.SymbolNotFound(name)
                if params.getRange().overlapsWith(diag.getRange()) =>
              importMissingSymbol(diag, name)
            // `foo.xxx` where `xxx` is not a member of `foo`
            // we search for `xxx` only when the target is Scala3
            // considering there might be an extension method.
            case d @ ScalacDiagnostic.NotAMember(name)
                if isScala3 && params.getRange().overlapsWith(d.getRange()) =>
              importMissingSymbol(d, name, findExtensionMethods = true)
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
                actions.foldLeft(actions.head.map(_.getTitle()).toSet) {
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
        importMissingSymbols(deduplicated.toSeq.sorted)
      }
  }

}

object ImportMissingSymbol {

  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  def allSymbolsTitle: String =
    s"Import all missing symbols that are unambiguous"
}
