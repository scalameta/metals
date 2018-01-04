package scala.meta.languageserver.refactoring

import cats.syntax.either._
import scala.meta.Document
import scala.meta.languageserver.Parser
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import scalafix.internal.rule.RemoveUnusedImports
import scala.meta.languageserver.ScalametaEnrichments._
import org.langmeta.lsp
import org.langmeta.lsp.ApplyWorkspaceEditParams
import org.langmeta.lsp.TextDocumentIdentifier
import org.langmeta.lsp.WorkspaceEdit
import org.langmeta.jsonrpc.Response
import scalafix.languageserver.ScalafixEnrichments._
import scalafix.languageserver.ScalafixPatchEnrichments._
import scalafix.rule.RuleCtx
import scalafix.util.SemanticdbIndex
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.lsp.TextDocumentIdentifier
import io.circe.Json

object OrganizeImports extends LazyLogging {

  val empty = ApplyWorkspaceEditParams(None, WorkspaceEdit(Map.empty))

  def removeUnused(
      arguments: Option[Seq[Json]],
      index: SymbolIndex
  ): Either[Response.Error, ApplyWorkspaceEditParams] = {
    val result = for {
      as <- arguments
      argument <- as.headOption
      textDocument <- argument.as[TextDocumentIdentifier].toOption
    } yield removeUnused(Uri(textDocument), index)
    Either.fromOption(
      result,
      Response.invalidParams(
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
      edit = lsp.WorkspaceEdit(Map(uri.value -> edits))
    )
  }
}
