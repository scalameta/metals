package scala.meta.languageserver.refactoring

import scala.meta._
import scala.meta.languageserver.Parser
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import scalafix.internal.rule.RemoveUnusedImports
import scala.meta.languageserver.ScalametaEnrichments._
import scalafix.languageserver.ScalafixEnrichments._
import scalafix.languageserver.ScalafixPatchEnrichments._
import scalafix.rule.RuleCtx
import scalafix.util.SemanticdbIndex
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.ApplyWorkspaceEditParams
import langserver.messages.InvalidParamsResponseError
import langserver.types.TextDocumentIdentifier
import langserver.types.WorkspaceEdit
import play.api.libs.json.JsValue
import play.api.libs.json.Json

object OrganizeImports extends LazyLogging {

  val empty = ApplyWorkspaceEditParams(None, WorkspaceEdit(Map.empty))

  def removeUnused(
      arguments: Option[Seq[JsValue]],
      index: SymbolIndex
  ): ApplyWorkspaceEditParams = {
    val result = for {
      as <- arguments
      argument <- as.headOption
      textDocument <- Json.fromJson[TextDocumentIdentifier](argument).asOpt
    } yield removeUnused(Uri(textDocument), index)
    result.getOrElse(
      throw InvalidParamsResponseError(
        s"Unable to parse TextDocumentIdentifier from $arguments"
      )
    )
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
