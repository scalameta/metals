package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.jpc.UnusedImportDiagnosticProvider
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class RemoveUnusedJavaImport(buffers: Buffers) extends CodeAction {
  import RemoveUnusedJavaImport._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    for {
      text <- buffers.get(path).orElse(path.readTextOpt).toSeq
      diagnostic <- params.getContext().getDiagnostics().asScala.toSeq
      if isUnusedImport(diagnostic)
      if range.overlapsWith(diagnostic.getRange())
      edit <- removeImportEdit(text, diagnostic.getRange()).toSeq
    } yield CodeActionBuilder.build(
      title,
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
  }
}

object RemoveUnusedJavaImport {
  val title = "Remove unused import"

  private def isUnusedImport(diagnostic: l.Diagnostic): Boolean =
    Option(diagnostic.getCode()).exists(code =>
      code.isLeft() &&
        code.getLeft() == UnusedImportDiagnosticProvider.UnusedImportCode
    )

  private def removeImportEdit(
      text: String,
      range: l.Range,
  ): Option[l.TextEdit] = {
    val lines = text.split("\n", -1)
    val startLine = range.getStart().getLine()
    if (startLine < 0 || startLine >= lines.length) None
    else {
      val endLine =
        if (
          startLine + 2 < lines.length &&
          lines(startLine + 1).isEmpty &&
          (startLine == 0 || lines(startLine - 1).isEmpty)
        ) startLine + 2
        else if (startLine + 1 < lines.length) startLine + 1
        else startLine
      val endCharacter =
        if (endLine == startLine) lines(startLine).length()
        else 0
      Some(
        new l.TextEdit(
          new l.Range(
            new l.Position(startLine, 0),
            new l.Position(endLine, endCharacter),
          ),
          "",
        )
      )
    }
  }
}
