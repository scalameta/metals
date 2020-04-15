package tests

import java.{util => ju}
import org.eclipse.lsp4j.Location
import java.util.Optional
import java.nio.file.Files
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.WorkspaceSymbolQuery

/**
 * Implementation of `SymbolSearch` for testing purposes.
 *
 * Currently empty implementation, since it is not usde in Scala 3
 */
class TestingSymbolSearch(
    classpath: ClasspathSearch = ClasspathSearch.empty,
    docs: Docstrings = Docstrings.empty,
    workspace: TestingWorkspaceSearch = TestingWorkspaceSearch.empty,
    index: GlobalSymbolIndex = null
) extends SymbolSearch {

  override def documentation(symbol: String): Optional[SymbolDocumentation] = {
    Optional.empty()
  }

  override def definition(symbol: String): ju.List[Location] = {
    new ju.LinkedList[Location]
  }

  override def definitionSourceToplevels(symbol: String): ju.List[String] =
    new ju.LinkedList[String]

  override def search(
      textQuery: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = SymbolSearch.Result.COMPLETE
}
