package scala.meta.languageserver.providers

import scala.meta.languageserver.WorkspaceCommand.ScalafixUnusedImports
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.CodeActionParams
import langserver.types.Command
import langserver.types.Diagnostic
import io.circe.syntax._

object CodeActionProvider extends LazyLogging {
  def codeActions(params: CodeActionParams): List[Command] = {
    params.context.diagnostics.collectFirst {
      case Diagnostic(_, _, _, Some("scalac"), "Unused import") =>
        Command(
          "Remove unused imports",
          ScalafixUnusedImports.entryName,
          params.textDocument.asJson :: Nil
        )
    }.toList
  }
}
