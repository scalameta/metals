package scala.meta.internal.pc

import java.util.Optional
import java.{util => ju}

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

  def definition(symbol: String): ju.List[Location] = {
    ju.Collections.emptyList()
  }

  override def definitionSourceToplevels(symbol: String): ju.List[String] = {
    ju.Collections.emptyList()
  }

  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    Optional.empty()
}
