package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.jpc.JavacDiagnostic
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.pc.CodeActionId

import org.eclipse.{lsp4j => l}

class ChangeVariableType(
    compilers: Compilers
) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true
  override def maybeCodeActionId: Option[String] =
    Some(CodeActionId.ChangeVariableType)

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val requestRange = params.getRange()

    val matchingDiagnostics =
      params.getContext().getDiagnostics().asScala.toSeq.collect {
        case diagnostic @ JavacDiagnostic.IncompatibleTypes()
            if requestRange.overlapsWith(diagnostic.getRange()) =>
          diagnostic
      }

    Future
      .sequence {
        matchingDiagnostics.map { diagnostic =>
          val editParams = new l.TextDocumentPositionParams(
            params.getTextDocument(),
            diagnostic.getRange().getStart(),
          )
          compilers
            .codeAction(
              editParams,
              token,
              CodeActionId.ChangeVariableType,
              Some(diagnostic.getRange()),
            )
            .map { edits =>
              if (edits.isEmpty()) None
              else Some(build(path, diagnostic, edits))
            }
        }
      }
      .map(_.flatten.distinctBy(_.getEdit()))
  }

  private def build(
      path: AbsolutePath,
      diagnostic: l.Diagnostic,
      edits: java.util.List[l.TextEdit],
  ): l.CodeAction = {
    CodeActionBuilder.build(
      ChangeVariableType.title,
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> edits.asScala.toSeq),
    )
  }
}

object ChangeVariableType {
  val title = "Change variable type to match assigned value"
}
