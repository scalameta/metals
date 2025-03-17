package scala.meta.internal.query

import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.GlobalSymbolIndex

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

/**
 * Query engine for searching symbols in the workspace and classpath.
 * Supports glob search, symbol inspection, and documentation retrieval.
 *
 * @param workspaceSearchProvider Provider for workspace symbol search
 */
class QueryEngine(
    workspaceSearchProvider: WorkspaceSymbolProvider,
    focusedDocumentBuildTarget: () => Option[BuildTargetIdentifier],
    index: GlobalSymbolIndex
) {

  /**
   * Search for symbols matching a glob pattern.
   *
   * @param query The search query (e.g., "matching" will search for "*matching*")
   * @param symbolTypes Set of symbol types to filter results (empty means all types)
   * @return Collection of matching symbols with their information
   */
  def globSearch(
      query: String,
      symbolTypes: Set[SymbolType] = Set.empty,
      enableDebug: Boolean = false,
  ): Seq[SymbolSearchResult] = {

    val visitor = new QuerySearchVisitor(
      index,
      symbolTypes,
      query,
      enableDebug,
    )
    // Create a query that will match the glob pattern
    val wsQuery = WorkspaceSymbolQuery(
      query,
      WorkspaceSymbolQuery.AlternativeQuery.all(query),
      isTrailingDot = false,
      isClasspath = true,
      isShortQueryRetry = false,
    )

    // use focused document build target
    workspaceSearchProvider.search(wsQuery, visitor, focusedDocumentBuildTarget())
    workspaceSearchProvider.searchWorkspacePackages(visitor, focusedDocumentBuildTarget())

    visitor.getResults
  }

  /**
   * Inspect a specific symbol to get detailed information.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @param deep Whether to perform a deep inspection
   * @return Information about the symbol
   */
  def inspect(
      fqcn: String,
      deep: Boolean = false,
  ): Option[SymbolInspectResult] = {
    // Implementation would need to:
    // 1. Find the symbol definition
    // 2. Extract information based on symbol type
    // 3. If deep=true, fetch nested information in multiple queries in parallel up to 3 levels deep

    // This is a placeholder - the actual implementation would
    // use the workspace provider and index to resolve the symbol
    // and extract detailed information about its members

    None // Placeholder - actual implementation would return meaningful results
  }

  /**
   * Retrieve documentation for a specific symbol.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @return Documentation for the symbol
   */
  def getDocumentation(fqcn: String): Option[SymbolDocumentation] = {
    // Implementation would need to:
    // 1. Find the symbol definition
    // 2. Extract the Scaladoc information

    // This is a placeholder - the actual implementation would
    // parse Scaladoc from the source file
    None // Placeholder - actual implementation would return meaningful results
  }

}

/**
 * Base trait for symbol search results.
 */
sealed trait SymbolSearchResult {
  def name: String
  def path: String
  def symbolType: SymbolType
}

/**
 * Result for package symbols.
 */
case class PackageSearchResult(name: String, path: String)
    extends SymbolSearchResult {
  val symbolType: SymbolType = SymbolType.Package
}

/**
 * Result for class or object symbols.
 */
case class ClassOrObjectSearchResult(
    name: String,
    path: String,
    symbolType: SymbolType,
) extends SymbolSearchResult

/**
 * Result for workspace symbols.
 */
case class WorkspaceSymbolSearchResult(
    name: String,
    path: String,
    symbolType: SymbolType,
    location: String,
) extends SymbolSearchResult

/**
 * Base trait for inspection results.
 */
sealed trait SymbolInspectResult {
  def name: String
  def path: String
  def symbolType: SymbolType
}

/**
 * Documentation for a symbol.
 */
case class SymbolDocumentation(
    description: String,
    parameters: List[(String, String)], // name -> description
    returnValue: String,
    examples: List[String],
)

/**
 * Symbol type for glob search.
 */
sealed trait SymbolType {
  def name: String
}

object SymbolType {
  case object Trait extends SymbolType {
    override def name: String = "trait"
  }
  case object Package extends SymbolType {
    override def name: String = "package"
  }
  case object Class extends SymbolType {
    override def name: String = "class"
  }
  case object Object extends SymbolType {
    override def name: String = "object"
  }
  case object Function extends SymbolType {
    override def name: String = "function"
  }
  case object Method extends SymbolType {
    override def name: String = "method"
  }

  case class Unknown(kind: String) extends SymbolType {
    override def name: String = kind
  }

  val values: List[SymbolType] =
    List(Trait, Package, Class, Object, Function, Method)
}
