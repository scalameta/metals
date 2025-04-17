package scala.meta.internal.metals.mcp

import java.nio.file.Path

import scala.collection.mutable

import scala.meta.internal.metals.Classfile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mcp.QueryEngine.kindToTypeString
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j
import org.eclipse.lsp4j.SymbolKind

class QuerySearchVisitor(
    mcpDefinitionProvider: McpDefinitionProvider,
    symbolTypes: Set[SymbolType],
    query: String,
    enableDebug: Boolean,
) extends SymbolSearchVisitor {
  private val lowerCaseQuery = query.toLowerCase
  private val results = mutable.ListBuffer.empty[SymbolSearchResult]
  def getResults: Seq[SymbolSearchResult] = results.toSeq

  override def shouldVisitPackage(pkg: String): Boolean = {
    val shouldIncludePackages =
      symbolTypes.isEmpty || symbolTypes.contains(SymbolType.Package)

    lazy val pkgName = pkg.substring(pkg.lastIndexOf('/') + 1)
    if (shouldIncludePackages && matchesQuery(pkgName)) {
      results +=
        SymbolSearchResult(
          name = pkgName,
          path = pkg.fqcn,
          SymbolType.Package,
          symbol = pkg,
        )
    }
    true // Continue searching even if this package doesn't match
  }

  override def visitWorkspacePackage(owner: String, name: String): Int = {
    if (matchesQuery(name)) {
      results += SymbolSearchResult(
        name,
        s"$owner$name".fqcn,
        SymbolType.Package,
        symbol = s"$owner$name",
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
      mcpDefinitionProvider.forEachDefinition(
        pkg,
        Classfile.name(filename),
        path => {
          val shouldVisit = !isVisited.contains(path)
          isVisited += path
          shouldVisit
        },
      ) { searchResult =>
        if (
          matchesQuery(searchResult.name) && (symbolTypes.isEmpty || symbolTypes
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
    val (desc, owner) = DescriptorParser(symbol)
    val symbolName = desc.name.value
    debug(
      s"Encountered workspace symbol: $symbol, desc: $desc, kind: $kind, range: $range, symbolName: $symbolName, owner: $owner"
    )

    lazy val symbolType =
      kindToTypeString(kind).getOrElse(SymbolType.Unknown(kind.toString))

    if (
      matchesQuery(symbolName) && (symbolTypes.isEmpty || symbolTypes.contains(
        symbolType
      ))
    ) {
      results +=
        SymbolSearchResult(
          name = symbolName,
          path = s"${owner.fqcn}.$symbolName",
          symbolType = symbolType,
          symbol = symbol,
        )
      1
    } else 0

  }

  private def debug(string: String): Unit = if (enableDebug) pprint.log(string)

  override def isCancelled(): Boolean = false

  private def matchesQuery(str: String): Boolean = {
    str.toLowerCase.contains(lowerCaseQuery)
  }
}
