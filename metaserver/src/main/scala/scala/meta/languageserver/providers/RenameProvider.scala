package scala.meta.languageserver.providers

import scala.meta._
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.Uri
import scala.meta.languageserver.refactoring.Backtick
import scala.meta.languageserver.search.SymbolIndex
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.messages.RenameResult
import langserver.messages.TextDocumentRenameRequest
import langserver.types.MessageType
import langserver.types.TextEdit
import langserver.types.WorkspaceEdit

object RenameProvider extends LazyLogging {
  def rename(
      request: TextDocumentRenameRequest,
      symbolIndex: SymbolIndex,
      notifications: Notifications
  ): RenameResult = Backtick.backtickWrap(request.params.newName) match {
    case Left(err) =>
      // LSP specifies that a ResponseError should be returned in this case
      // but it seems when we do that at least vscode doesn't display the error
      // message, only "No result" is displayed to the user, which is not helpful.
      // I prefer to use showMessage to explain what went wrong and perform
      // no text edit.
      notifications.showMessage(MessageType.Warning, err)
      RenameResult(WorkspaceEdit(Map.empty))
    case Right(newName) =>
      val uri = Uri(request.params.textDocument.uri)
      val edits = for {
        symbol <- symbolIndex
          .findSymbol(
            uri,
            request.params.position.line,
            request.params.position.character
          )
          .toList
        if {
          symbol match {
            case _: Symbol.Local => true
            case _ =>
              notifications.showMessage(
                MessageType.Warning,
                s"Rename for global symbol $symbol is not supported yet. " +
                  s"Only local symbols can be renamed."
              )
              false
          }
        }
        data <- symbolIndex.referencesData(symbol)
        reference <- data.referencePositions(true)
      } yield {
        val location = reference.toLocation
        TextEdit(location.range, newName)
      }
      // NOTE(olafur) uri.value is hardcoded here because local symbols
      // cannot be references across multiple files. When we add support for
      // renaming global symbols then this needs to change.
      RenameResult(WorkspaceEdit(Map(uri.value -> edits)))
  }
}
