package scala.meta.internal.metals.codeactions

import org.eclipse.{lsp4j => l}
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

object CodeActionBuilder {
  def build(
      title: String,
      kind: String,
      changes: Seq[(AbsolutePath, Seq[l.TextEdit])] = Nil,
      documentChanges: List[Either[l.TextDocumentEdit, l.ResourceOperation]] =
        Nil,
      command: Option[l.Command] = None,
      diagnostics: List[l.Diagnostic] = Nil,
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(title)
    codeAction.setKind(kind)

    val workspaceEdits = new l.WorkspaceEdit()
    workspaceEdits.setChanges(
      changes
        .map { case (path, edits) =>
          path.toURI.toString -> edits.asJava
        }
        .toMap
        .asJava
    )
    workspaceEdits.setDocumentChanges(documentChanges.map(_.asJava).asJava)

    codeAction.setEdit(workspaceEdits)
    command.foreach(codeAction.setCommand)
    codeAction.setDiagnostics(diagnostics.asJava)
    codeAction
  }
}
