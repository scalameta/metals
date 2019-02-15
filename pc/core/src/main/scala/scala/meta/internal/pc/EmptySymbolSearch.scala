package scala.meta.internal.pc

import java.util.Optional
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

object EmptySymbolSearch extends SymbolSearch {
  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def documentation(symbol: String): Optional[SymbolDocumentation] =
    Optional.empty()
}
