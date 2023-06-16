package scala.meta.internal.metals.codeactions

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.logging
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

object CodeActionBuilder {
  type DocumentChange = Either[l.TextDocumentEdit, l.ResourceOperation]

  def build(
      title: String,
      kind: String,
      changes: Seq[(AbsolutePath, Seq[l.TextEdit])] = Nil,
      documentChanges: List[DocumentChange] = Nil,
      command: Option[l.Command] = None,
      diagnostics: List[l.Diagnostic] = Nil,
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(title)
    codeAction.setKind(kind)

    logging.logErrorWhen(
      changes.nonEmpty && documentChanges.nonEmpty,
      "Only changes or documentChanges can be set in code action at the same time",
    )

    val workspaceEdits = new l.WorkspaceEdit()
    if (changes.nonEmpty)
      workspaceEdits.setChanges(
        changes
          .map { case (path, edits) =>
            path.toURI.toString -> edits.asJava
          }
          .toMap
          .asJava
      )
    else if (documentChanges.nonEmpty)
      workspaceEdits.setDocumentChanges(documentChanges.map(_.asJava).asJava)

    codeAction.setEdit(workspaceEdits)
    command.foreach(codeAction.setCommand)
    codeAction.setDiagnostics(diagnostics.asJava)
    codeAction
  }
}
