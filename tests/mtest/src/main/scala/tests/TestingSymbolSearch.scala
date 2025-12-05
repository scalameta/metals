package tests

import java.net.URI
import java.util.Optional
import java.{util => ju}

import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.WorkspaceSymbolInformation
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.{semanticdb => s}
import scala.meta.pc.{ContentType, MemberKind}
import scala.meta.pc.ParentSymbols
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.pc.reports.EmptyReportContext
import scala.meta.pc.reports.ReportContext

import org.eclipse.lsp4j.Location

/**
 * Implementation of `SymbolSearch` for testing purposes.
 *
 * We can't use `MetalsSymbolSearch` because it relies on WorkspaceSymbolProvider, which is 2.12 only.
 */
class TestingSymbolSearch(
    classpath: ClasspathSearch = ClasspathSearch.empty,
    docs: Docstrings = Docstrings.empty(new EmptyReportContext()),
    workspace: TestingWorkspaceSearch =
      TestingWorkspaceSearch.empty(new EmptyReportContext()),
    index: GlobalSymbolIndex =
      OnDemandSymbolIndex.empty()(new EmptyReportContext())
)(implicit rc: ReportContext = new EmptyReportContext())
    extends SymbolSearch {

  override def documentation(
      symbol: String,
      parents: ParentSymbols
  ): Optional[SymbolDocumentation] =
    documentation(symbol, parents, ContentType.MARKDOWN)

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
      contentType: ContentType
  ): Optional[SymbolDocumentation] = {
    docs.documentation(symbol, parents, contentType)
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
        import scala.meta.internal.jdk.CollectionConverters._
        Mtags.topLevelSymbols(value.path).asJava
    }
  }

  override def search(
      textQuery: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    search(textQuery, buildTargetIdentifier, ju.Optional.empty(), visitor)
  }

  override def search(
      textQuery: String,
      buildTargetIdentifier: String,
      kind: ju.Optional[ToplevelMemberKind],
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    val query = WorkspaceSymbolQuery.exact(textQuery)
    workspace.search(query, visitor)
    if (!kind.isPresent || kind.get() != ToplevelMemberKind.IMPLICIT_CLASS)
      classpath.search(query, visitor)._1
    else SymbolSearch.Result.COMPLETE
  }

  override def searchMethods(
      textQuery: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    val query = WorkspaceSymbolQuery.exact(textQuery)
    workspace.search(
      query,
      visitor,
      (info: WorkspaceSymbolInformation) => {
        info.sematicdbKind == s.SymbolInformation.Kind.METHOD
      }
    )
    SymbolSearch.Result.COMPLETE
  }
}
