package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}
import scala.meta.internal.parsing.Trees

class AddMissingMatchCase(
    trees: Trees,
    compilers: Compilers,
    buildTargets: BuildTargets,
) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val uri = params.getTextDocument().getUri()

    Future
      .sequence(
        params
          .getContext()
          .getDiagnostics()
          .asScala
          .collect {
            case diag @ ScalacDiagnostic.MatchMightNotBeExhaustive(missingCases)
                if params.getRange().overlapsWith(diag.getRange()) =>
              val tc = new l.TextEdit(diag.getRange(), missingCases.head)
              Future.successful(
                CodeActionBuilder.build(
                  title = "Add missing cases",
                  kind = l.CodeActionKind.QuickFix,
                  changes = List(uri.toAbsolutePath -> Seq(tc)),
                )
              )
          }
          .toList
      )
  }
}

object AddMissingMatchCase {

  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  def allSymbolsTitle: String =
    s"Import all missing symbols that are unambiguous"
}
