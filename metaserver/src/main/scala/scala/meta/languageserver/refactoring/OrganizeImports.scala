package scala.meta.languageserver.refactoring

import scala.meta.Document
import scala.meta.languageserver.Parser
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import scalafix.internal.rule.RemoveUnusedImports
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.protocol.Response
import scalafix.languageserver.ScalafixEnrichments._
import scalafix.languageserver.ScalafixPatchEnrichments._
import scalafix.rule.RuleCtx
import scalafix.util.SemanticdbIndex
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.ApplyWorkspaceEditParams
import langserver.types.TextDocumentIdentifier
import langserver.types.WorkspaceEdit
import monix.eval.Task
import io.circe.Json

object OrganizeImports extends LazyLogging {

  val empty = ApplyWorkspaceEditParams(None, WorkspaceEdit(Map.empty))

  def removeUnused(
      arguments: Option[Seq[Json]],
      index: SymbolIndex
  ): Task[Either[Response.Error, ApplyWorkspaceEditParams]] = {
    val result = for {
      as <- arguments
      argument <- as.headOption
      textDocument <- argument.as[TextDocumentIdentifier].toOption
    } yield removeUnused(Uri(textDocument), index)
    Task {
      result match {
        case Some(x) =>
          Right(x)
        case None =>
          Left(
            Response.invalidParams(
              s"Unable to parse TextDocumentIdentifier from $arguments"
            )
          )
      }
    }
  }

  def removeUnused(uri: Uri, index: SymbolIndex): ApplyWorkspaceEditParams = {
    index.documentIndex.getDocument(uri) match {
      case Some(document) =>
        removeUnused(uri, document.toMetaDocument)
      case None => empty
    }
  }

  def removeUnused(uri: Uri, document: Document): ApplyWorkspaceEditParams = {
    val index = SemanticdbIndex.load(document)
    val rule = RemoveUnusedImports(index)
    val tree = Parser.parse(document.input).get
    val ctx = RuleCtx(tree)
    val patch = rule.fixWithNameInternal(ctx).values.asPatch
    val edits = patch.toTextEdits(ctx, index)
    ApplyWorkspaceEditParams(
      label = Some(s"Remove unused imports"),
      edit = WorkspaceEdit(Map(uri.value -> edits))
    )
  }
}
