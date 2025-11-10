package scala.meta.internal.metals

import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsIndexer
import scala.meta.internal.mtags.ScalaToplevelMtags
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.mtags.proto.ProtobufToplevelMtags
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
  def foreach(
      mtags: Mtags,
      input: Input.VirtualFile,
      dialect: Dialect,
      includeMembers: Boolean
  )(
      fn: SemanticdbDefinition => Unit
  )(implicit rc: ReportContext): Unit =
    foreachWithReturnMtags(
      mtags,
      input,
      dialect,
      includeMembers,
      collectIdentifiers = false
    )(fn)

  def foreachWithReturnMtags(
      mtags: Mtags,
      input: Input.VirtualFile,
      dialect: Dialect,
      includeMembers: Boolean,
      collectIdentifiers: Boolean
  )(
      fn: SemanticdbDefinition => Unit
  )(implicit rc: ReportContext): Option[MtagsIndexer] = {
    input.toJLanguage match {
      case Semanticdb.Language.SCALA =>
        val indexer = new ScalaToplevelMtags(
          input,
          includeInnerClasses = true,
          includeMembers = includeMembers,
          dialect,
          collectIdentifiers = collectIdentifiers
        ) {
          override def visitOccurrence(
              occ: SymbolOccurrence,
              info: SymbolInformation,
              owner: String
          ): Unit = {
            fn(SemanticdbDefinition(info, occ, owner))
          }
        }
        try indexer.indexRoot()
        catch {
          case _: TokenizeException =>
            () // ignore because we don't need to index untokenizable files.
        }
        Some(indexer)
      case Semanticdb.Language.JAVA =>
        val indexer = mtags.config.javaInstanceWithOccurrenceVisitor(
          input,
          includeMembers = includeMembers
        )((occ, info, owner) => fn(SemanticdbDefinition(info, occ, owner)))
        try indexer.indexRoot()
        catch {
          case NonFatal(_) =>
        }
        Some(indexer)
      case Semanticdb.Language.PROTOBUF =>
        val indexer =
          new ProtobufToplevelMtags(input, includeGeneratedSymbols = true) {
            override def visitOccurrence(
                occ: SymbolOccurrence,
                info: SymbolInformation,
                owner: String
            ): Unit = {
              fn(SemanticdbDefinition(info, occ, owner))
            }
          }
        try indexer.indexRoot()
        catch {
          case NonFatal(_) =>
        }
        Some(indexer)
      case _ => None
    }
  }
}
