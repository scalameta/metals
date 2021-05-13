package scala.meta.internal.metals.codeactions

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams

trait CodeActionCommandData {
  val uri: String
  val actionType: String
}

case class ExtractMemberDefinitionData(
    uri: String,
    params: TextDocumentPositionParams,
    actionType: String = ExtractRenameMember.extractDefCommandDataType
) extends CodeActionCommandData

case class CodeActionCommandResult(
    edits: ApplyWorkspaceEditParams,
    goToLocation: Option[Location]
)
