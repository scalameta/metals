package scala.meta.internal.metals.codeactions

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.logging
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import org.eclipse.{lsp4j => l}

object CodeActionBuilder {
  type DocumentChange = Either[l.TextDocumentEdit, l.ResourceOperation]

  def build(
      title: String,
      kind: String,
      changes: Seq[(AbsolutePath, Seq[l.TextEdit])] = Nil,
      documentChanges: List[DocumentChange] = Nil,
      command: Option[l.Command] = None,
      data: Option[JsonObject] = None,
      diagnostics: List[l.Diagnostic] = Nil,
      disabledReason: Option[String] = None,
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(title)
    codeAction.setKind(kind)

    logging.logErrorWhen(
      changes.nonEmpty && documentChanges.nonEmpty,
      "Only changes or documentChanges can be set in code action at the same time",
    )

    // we don't want to set edit to make it resolve lazily in codeAction/resolve
    if (changes.nonEmpty) {
      val workspaceEdits = new l.WorkspaceEdit()
      workspaceEdits.setChanges(
        changes
          .map { case (path, edits) =>
            path.toURI.toString -> edits.asJava
          }
          .toMap
          .asJava
      )
      codeAction.setEdit(workspaceEdits)
    } else if (documentChanges.nonEmpty) {
      val workspaceEdits = new l.WorkspaceEdit()
      workspaceEdits.setDocumentChanges(documentChanges.map(_.asJava).asJava)
      codeAction.setEdit(workspaceEdits)
    }
    command.foreach(codeAction.setCommand)
    data.foreach(codeAction.setData)
    disabledReason.foreach(reason =>
      codeAction.setDisabled(new l.CodeActionDisabled(reason))
    )
    codeAction.setDiagnostics(diagnostics.asJava)
    codeAction
  }
}
