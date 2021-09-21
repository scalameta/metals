package scala.meta.internal.metals.codeactions

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams

trait CodeActionCommandData {
  def uri: String
  val actionType: String
}

case class ExtractMemberDefinitionData(
    params: TextDocumentPositionParams,
    actionType: String = ExtractRenameMember.extractDefCommandDataType
) extends CodeActionCommandData {

  override def uri: String = params.getTextDocument().getUri()
}

case class CodeActionCommandResult(
    edits: ApplyWorkspaceEditParams,
    goToLocation: Option[Location]
)
