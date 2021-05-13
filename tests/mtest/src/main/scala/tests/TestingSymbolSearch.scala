package tests

import java.net.URI
import java.nio.file.Files
import java.util.Optional
import java.{util => ju}

import scala.meta.inputs.Input
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j.Location

/**
 * Implementation of `SymbolSearch` for testing purposes.
 *
 * We can't use `MetalsSymbolSearch` because it relies on WorkspaceSymbolProvider, which is 2.12 only.
 */
class TestingSymbolSearch(
    classpath: ClasspathSearch = ClasspathSearch.empty,
    docs: Docstrings = Docstrings.empty,
    workspace: TestingWorkspaceSearch = TestingWorkspaceSearch.empty,
    index: GlobalSymbolIndex = OnDemandSymbolIndex.empty()
) extends SymbolSearch {
  override def documentation(symbol: String): Optional[SymbolDocumentation] = {
    docs.documentation(symbol)
  }

  override def definition(symbol: String, source: URI): ju.List[Location] = {
    index.definition(Symbol(symbol)) match {
      case None =>
        ju.Collections.emptyList()
      case Some(value) =>
        import org.eclipse.lsp4j.Range
        import org.eclipse.lsp4j.Position
        val filename = value.path.toNIO.getFileName().toString()
        val uri = s"$symbol $filename"
        ju.Collections.singletonList(
          new Location(
            uri,
            new Range(new Position(0, 0), new Position(0, 0))
          )
        )
    }
  }

  override def definitionSourceToplevels(
      symbol: String,
      source: URI
  ): ju.List[String] = {
    index.definition(Symbol(symbol)) match {
      case None =>
        ju.Collections.emptyList()
      case Some(value) =>
        import scala.collection.JavaConverters._
        val filename = value.path.toNIO.getFileName().toString()
        val content = new String(Files.readAllBytes(value.path.toNIO))
        val input = Input.VirtualFile(
          filename,
          content
        )
        Mtags.toplevels(input).asJava
    }
  }

  override def search(
      textQuery: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    val query = WorkspaceSymbolQuery.exact(textQuery)
    workspace.search(query, visitor)
    classpath.search(query, visitor)
  }
}
