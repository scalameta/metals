package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.JavaMtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.ScalaToplevelMtags
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.tokenizers.TokenizeException
import scala.util.control.NonFatal

/**
 * A definition of a global symbol produced by mtags.
 */
case class SemanticdbDefinition(
    info: SymbolInformation,
    occ: SymbolOccurrence,
    owner: String
) {
  def toLSP(uri: String): l.SymbolInformation = {
    new l.SymbolInformation(
      info.displayName,
      info.kind.toLSP,
      new l.Location(uri, occ.range.get.toLSP),
      owner.replace('/', '.')
    )
  }
}

object SemanticdbDefinition {
  def foreach(input: Input.VirtualFile)(
      fn: SemanticdbDefinition => Unit
  ): Unit = {
    input.toLanguage match {
      case Language.SCALA =>
        val mtags = new ScalaToplevelMtags(input, true) {
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
          case NonFatal(e) =>
        }
      case _ =>
    }
  }
}
