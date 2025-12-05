package scala.meta.internal.pc

import java.net.URI
import java.util.Optional
import java.{util => ju}

import scala.meta.pc.{ContentType, MemberKind}
import scala.meta.pc.ParentSymbols
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import org.eclipse.lsp4j.Location

object EmptySymbolSearch extends SymbolSearch {
  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def search(
      query: String,
      buildTargetIdentifier: String,
      kind: ju.Optional[ToplevelMemberKind],
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def searchMethods(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def definition(symbol: String, source: URI): ju.List[Location] = {
    ju.Collections.emptyList()
  }

  override def definitionSourceToplevels(
      symbol: String,
      sourceUri: URI
  ): ju.List[String] = {
    ju.Collections.emptyList()
  }

  override def documentation(
      symbol: String,
      parents: ParentSymbols
  ): Optional[SymbolDocumentation] =
    Optional.empty()

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
      docstringContentType: ContentType
  ): Optional[SymbolDocumentation] = Optional.empty()
}
