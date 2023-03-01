package scala.meta.internal.pc

import org.eclipse.lsp4j.Location

import java.net.URI
import java.util.Optional
import java.{util => ju}
import scala.meta.pc.{
  ParentSymbols,
  SymbolDocumentation,
  SymbolSearch,
  SymbolSearchVisitor,
}

object EmptySymbolSearch extends SymbolSearch {
  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def searchMethods(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def definition(symbol: String, source: URI): ju.List[Location] = {
    ju.Collections.emptyList()
  }

  override def definitionSourceToplevels(
      symbol: String,
      sourceUri: URI,
  ): ju.List[String] = {
    ju.Collections.emptyList()
  }

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
  ): Optional[SymbolDocumentation] =
    Optional.empty()
}
