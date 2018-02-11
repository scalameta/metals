package scala.meta.metals.providers

import scala.meta.metals.WorkspaceCommand.ScalafixUnusedImports
import org.langmeta.lsp.CodeActionParams
import org.langmeta.lsp.Command
import org.langmeta.lsp.Diagnostic
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.lsp.Command
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
