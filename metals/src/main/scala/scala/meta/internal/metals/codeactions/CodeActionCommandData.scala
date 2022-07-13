package scala.meta.internal.metals.codeactions

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Location

// needed? TODO
case class CodeActionCommandResult(
    edits: ApplyWorkspaceEditParams,
    goToLocation: Option[Location],
)
