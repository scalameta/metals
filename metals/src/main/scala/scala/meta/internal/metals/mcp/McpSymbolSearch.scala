package scala.meta.internal.metals.mcp

import java.nio.file.Path

import scala.collection.mutable

import scala.meta.Dialect
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Classfile
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.metals.mcp.McpQueryEngine.kindToTypeString
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j
import org.eclipse.lsp4j.SymbolKind

class McpSymbolSearch(
    index: GlobalSymbolIndex,
    buildTargets: BuildTargets,
    workspaceSearchProvider: WorkspaceSymbolProvider,
) {

  def nameSearch(
      query: String,
      symbolTypes: Set[SymbolType] = Set.empty,
      path: Option[AbsolutePath],
  ): Seq[SearchResult] = {
    val lowerCaseQuery = query.toLowerCase
    val matches: String => Boolean = { symbol =>
      val fqcn = symbol.fqcn
      val lastPart = fqcn.split('.').lastOption.getOrElse(fqcn)
      lastPart.toLowerCase.contains(lowerCaseQuery)
    }
    search(
      WorkspaceSymbolQuery.fromTextQuery(query),
      matches,
      symbolTypes,
      path,
    )
  }

  def exactSearch(
      query: String,
      path: Option[AbsolutePath],
  ): Seq[SearchResult] = {
    val matches: String => Boolean = { symbol =>
      symbol.fqcn == query
    }
    search(WorkspaceSymbolQuery.exact(query), matches, Set.empty, path)
  }

  private def search(
      wsQuery: WorkspaceSymbolQuery,
      matches: String => Boolean,
      symbolTypes: Set[SymbolType],
      path: Option[AbsolutePath],
  ): Seq[SearchResult] = {

    val visitor = new McpSearchVisitor(
      index,
      symbolTypes,
      matches,
    )

    // Smart fallback: if no path provided, use first available build target
    // Exclude build meta-targets (e.g., root-build) as they don't have project dependencies
    val buildTarget =
      path
        .flatMap(buildTargets.sourceBuildTargets)
        .flatMap(_.headOption)
        .orElse {
          buildTargets.allBuildTargetIds
            .filterNot(McpQueryEngine.isBuildMetaTarget(buildTargets, _))
            .headOption
        }

    // use focused document build target (or fallback)
    workspaceSearchProvider.search(
      wsQuery,
      visitor,
      buildTarget,
    )
    workspaceSearchProvider.searchWorkspacePackages(
      visitor,
      buildTarget,
    )

    visitor.getResults
  }
}

private[mcp] class McpSearchVisitor(
    index: GlobalSymbolIndex,
    symbolTypes: Set[SymbolType],
    matches: String => Boolean,
) extends SymbolSearchVisitor {
  private val results = mutable.ListBuffer.empty[SearchResult]
  def getResults: Seq[SearchResult] = results.toSeq

  override def shouldVisitPackage(pkg: String): Boolean = {
    val shouldIncludePackages =
      symbolTypes.isEmpty || symbolTypes.contains(SymbolType.Package)

    if (shouldIncludePackages && matches(pkg)) {
      results +=
        SearchResult(
          symbol = pkg,
          SymbolType.Package,
          definitionPath = None,
        )
    }
    true // Continue searching even if this package doesn't match
  }

  override def visitWorkspacePackage(pkg: String): Int = {
    if (matches(pkg)) {
      results += SearchResult(
        symbol = pkg,
        SymbolType.Package,
        definitionPath = None,
      )
      1
    } else 0
  }

  private val isVisited: mutable.Set[AbsolutePath] =
    mutable.Set.empty[AbsolutePath]

  override def visitClassfile(pkg: String, filename: String): Int = {
    // Only process the classfile if we're interested in classes/objects
    if (
      symbolTypes.isEmpty ||
      symbolTypes.exists(t =>
        t == SymbolType.Class || t == SymbolType.Object || t == SymbolType.Trait
      )
    ) {

      var size = 0
      forEachDefinition(
        pkg,
        Classfile.name(filename),
        path => {
          val shouldVisit = !isVisited.contains(path)
          isVisited += path
          shouldVisit
        },
      ) { searchResult =>
        if (
          matches(searchResult.symbol) && (symbolTypes.isEmpty || symbolTypes
            .contains(searchResult.symbolType))
        ) {
          results += searchResult
          size += 1
        }
      }
      size
    } else 0
  }

  override def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: SymbolKind,
      range: lsp4j.Range,
  ): Int = {
    lazy val symbolType =
      kindToTypeString(kind).getOrElse(SymbolType.Unknown(kind.toString))

    if (
      matches(symbol) && (symbolTypes.isEmpty || symbolTypes.contains(
        symbolType
      ))
    ) {
      results +=
        SearchResult(
          symbol = symbol,
          symbolType = symbolType,
          definitionPath = Some(AbsolutePath(path)),
        )
      1
    } else 0

  }

  override def isCancelled(): Boolean = false

  def forEachDefinition(
      pkg: String,
      name: String,
      shouldVisitFile: AbsolutePath => Boolean = _ => true,
      includeMembers: Boolean = false,
  )(f: SearchResult => Unit): Unit = {
    definition(pkg, name).foreach {
      case (path, dialect) if shouldVisitFile(path) =>
        val input = path.toInput
        SemanticdbDefinition.foreach(
          input,
          dialect,
          includeMembers,
        ) { semanticdbDefn =>
          lazy val kind =
            kindToTypeString(semanticdbDefn.info.kind.toLsp).getOrElse(
              SymbolType.Unknown(semanticdbDefn.info.kind.toString)
            )
          val searchResult = SearchResult(
            symbol = semanticdbDefn.info.symbol,
            symbolType = kind,
            definitionPath = Some(path),
          )
          f(searchResult)
        }(EmptyReportContext)
      case _ =>
    }
  }

  private def definition(
      pkg: String,
      nme: String,
  ): List[(AbsolutePath, Dialect)] = {
    val tpe = Symbol(Symbols.Global(pkg, Descriptor.Type(nme)))
    val forTpe = index.findFileForToplevel(tpe)
    val term = Symbol(Symbols.Global(pkg, Descriptor.Term(nme)))
    val forTerm = index.findFileForToplevel(term)
    (forTpe ++ forTerm).distinctBy(_._1)
  }
}

private[mcp] case class SearchResult(
    symbol: String,
    symbolType: SymbolType,
    definitionPath: Option[AbsolutePath],
) {
  def toMcpSymbolSearchResult: SymbolSearchResult = {
    SymbolSearchResult(symbol.fqcn, symbolType, symbol)
  }
}
