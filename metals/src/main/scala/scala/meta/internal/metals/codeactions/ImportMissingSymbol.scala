package scala.meta.internal.metals.codeactions

import java.util.Collections

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
        .getOrDefault(uri, Collections.emptyList)
        .asScala

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

    def importMissingSymbol(
        diagnostic: l.Diagnostic,
        name: String,
        findExtensionMethods: Boolean = false,
    ): Future[Seq[l.CodeAction]] = {
      val textDocumentPositionParams = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        diagnostic.getRange.getEnd(),
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

      if (uniqueCodeActions.length > 1) {

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
        val deduplicated = actions.flatten
          .groupBy(_.getTitle())
          .map { case (_, actions) =>
            val mainAction = actions.head
            val allDiagnostics =
              actions.flatMap(_.getDiagnostics().asScala).asJava
            val edits = joinActionEdits(actions.toSeq)
            mainAction.setDiagnostics(allDiagnostics)
            mainAction
              .setEdit(new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava))
            mainAction
          }
          .toSeq
          .sorted
        importMissingSymbols(deduplicated.toSeq)
      }
  }

}

object ImportMissingSymbol {

  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  def allSymbolsTitle: String =
    s"Import all missing symbols that are unambiguous"
}
