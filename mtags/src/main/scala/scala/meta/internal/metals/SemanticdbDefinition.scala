package scala.meta.internal.metals

import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.mtags.JavaMtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.ScalaToplevelMtags
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.{semanticdb => s}
import scala.meta.tokenizers.TokenizeException

import org.eclipse.{lsp4j => l}

/**
 * A definition of a global symbol produced by mtags.
 */
case class SemanticdbDefinition(
    info: SymbolInformation,
    occ: SymbolOccurrence,
    owner: String
) {
  def toCached: WorkspaceSymbolInformation = {
    val range = occ.range.getOrElse(s.Range())
    WorkspaceSymbolInformation(info.symbol, info.kind, range.toLsp)
  }
  def toLsp(uri: String): l.SymbolInformation = {
    new l.SymbolInformation(
      info.displayName,
      info.kind.toLsp,
      new l.Location(uri, occ.range.get.toLsp),
      owner.replace('/', '.')
    )
  }
}

object SemanticdbDefinition {
  def foreach(input: Input.VirtualFile, dialect: Dialect)(
      fn: SemanticdbDefinition => Unit
  ): Unit = {
    input.toLanguage match {
      case Language.SCALA =>
        val mtags = new ScalaToplevelMtags(input, true, dialect) {
          override def visitOccurrence(
              occ: SymbolOccurrence,
              info: SymbolInformation,
              owner: String
          ): Unit = {
            fn(SemanticdbDefinition(info, occ, owner))
          }
        }
        try mtags.indexRoot()
        catch {
          case _: TokenizeException =>
            () // ignore because we don't need to index untokenizable files.
        }
      case Language.JAVA =>
        val mtags = new JavaMtags(input) {
          override def visitOccurrence(
              occ: SymbolOccurrence,
              info: SymbolInformation,
              owner: String
          ): Unit = {
            fn(SemanticdbDefinition(info, occ, owner))
          }
        }
        try mtags.indexRoot()
        catch {
          case NonFatal(_) =>
        }
      case _ =>
    }
  }
}
