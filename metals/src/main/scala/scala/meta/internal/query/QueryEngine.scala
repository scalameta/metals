package scala.meta.internal.query

import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.pc.SymbolSearchVisitor
import java.nio.file.Path
import org.eclipse.lsp4j
import org.eclipse.lsp4j.SymbolKind
import scala.collection.mutable.ListBuffer
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala.Symbols

/**
 * Query engine for searching symbols in the workspace and classpath.
 * Supports glob search, symbol inspection, and documentation retrieval.
 *
 * @param workspaceSearchProvider Provider for workspace symbol search
 */
class QueryEngine(
    workspaceSearchProvider: WorkspaceSymbolProvider
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
    val results = ListBuffer.empty[SymbolSearchResult]

    val visitor = new SymbolSearchVisitor {
      override def shouldVisitPackage(pkg: String): Boolean = {
        val shouldIncludePackages = symbolTypes.isEmpty ||
          symbolTypes.contains(SymbolType.Package)

        if (enableDebug) {
          pprint.log(
            s"Encountered package: $pkg, query: $query, shouldIncludePackages: $shouldIncludePackages, containsIgnoreCase: ${containsIgnoreCase(pkg, query)}"
          )
        }
        if (shouldIncludePackages && containsIgnoreCase(pkg, query)) {
          results += PackageSearchResult(
            name = pkg.substring(pkg.lastIndexOf('.') + 1),
            path = pkg,
          )
        }
        true // Continue searching even if this package doesn't match
      }

      override def visitClassfile(pkg: String, filename: String): Int = {
        // Only process the classfile if we're interested in classes/objects
        if (
          symbolTypes.isEmpty ||
          symbolTypes.exists(t =>
            t == SymbolType.Class || t == SymbolType.Object || t == SymbolType.Trait
          )
        ) {
          val className = filename.stripSuffix(".class")
          if (containsIgnoreCase(className, query)) {
            val fqcn = s"$pkg.$className"
            // Determine if this is a class or object based on filename
            // (This is simplified, real implementation would need to check the actual type)
            val symbolType =
              if (className.endsWith("$")) SymbolType.Object
              else SymbolType.Trait

            if (symbolTypes.isEmpty || symbolTypes.contains(symbolType)) {
              results += ClassOrObjectSearchResult(
                name = className.stripSuffix("$"),
                path = fqcn,
                symbolType = symbolType,
              )

              return 1
            }
          }
        }

        0
      }

      override def visitWorkspaceSymbol(
          path: Path,
          symbol: String,
          kind: SymbolKind,
          range: lsp4j.Range,
      ): Int = {
        val (desc, owner) = DescriptorParser(symbol)
        val symbolName = desc.name.value

        val startSize = results.size

        if (enableDebug) {
          pprint.log(
            s"Encountered workspace symbol: $symbol, desc: $desc, kind: $kind, range: $range, symbolName: $symbolName, owner: $owner"
          )
        }

        if (containsIgnoreCase(symbolName, query)) {
          val symbolType =
            kindToTypeString(kind).getOrElse(SymbolType.Unknown(kind.toString))

          if (symbolTypes.isEmpty) {
            results += WorkspaceSymbolSearchResult(
              name = symbolName,
              path = s"${owner.replace('/', '.')}.$symbolName",
              symbolType = symbolType,
              location = path.toUri.toString,
            )
          } else if (symbolTypes.contains(symbolType)) {
            results += WorkspaceSymbolSearchResult(
              name = symbolName,
              path = s"${owner.replace('/', '.')}.$symbolName",
              symbolType = symbolType,
              location = path.toUri.toString,
            )
          }

          val endSize = results.size

          endSize - startSize
        }

        0
      }

      override def isCancelled(): Boolean = false
    }

    // Create a query that will match the glob pattern
    val wsQuery = WorkspaceSymbolQuery(
      query,
      WorkspaceSymbolQuery.AlternativeQuery.all(query),
      isTrailingDot = false,
      isClasspath = true,
      isShortQueryRetry = false,
    )

    // should we run this on all build targets? doesn't it mean we'll get stuff from
    // test scope in compile scope?
    val buildTargets = workspaceSearchProvider.buildTargets.allBuildTargetIds
    buildTargets.foreach { target =>
      workspaceSearchProvider.search(wsQuery, visitor, Some(target))
    }

    results.toSeq
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

  /**
   * Find packages matching the given name.
   *
   * @param packageName The package name to search for
   * @return Set of matching package names
   */
  def findPackage(
      packageName: String
  ): Set[String] = {
    val packages = ListBuffer.empty[String]

    // I think we might only index top level symbols such as classes
    val visitor = new SymbolSearchVisitor {
      override def shouldVisitPackage(pkg: String): Boolean = {
        if (pkg.contains(packageName)) {
          // we can capture packages this way, we don't notmally index them since they don't have a place for definition
          packages += pkg
          true
        } else {
          false
        }
      }

      /* dependencies, more advanced example in WorkspaceSearchVisitor
       * We usually construct semanticdb symbols from classfiles, then go to definition
       * to find the source file, which we can then easily index using ScalaMtags or JavaMtags
       *
       */
      override def visitClassfile(pkg: String, filename: String): Int = {
        0
      }

      // For anything in local workspace
      override def visitWorkspaceSymbol(
          path: Path,
          symbol: String,
          kind: SymbolKind,
          range: lsp4j.Range,
      ): Int = {

        pprint.log(symbol)
        0
      }

      override def isCancelled(): Boolean = false

    }
    // matches method can be modified to better suite the needs
    val query = WorkspaceSymbolQuery(
      packageName,
      WorkspaceSymbolQuery.AlternativeQuery.all(packageName),
      false,
      true,
    )
    val id = workspaceSearchProvider.buildTargets.allBuildTargetIds.headOption
    workspaceSearchProvider.search(
      query,
      visitor,
      id,
    )
    val set = packages.toList.toSet
    set
  }

  // Helper methods

  /**
   * Check if a string contains another string, ignoring case.
   */
  private def containsIgnoreCase(str: String, query: String): Boolean = {
    str.toLowerCase.contains(query.toLowerCase)
  }

  /**
   * Convert SymbolKind to a string representation.
   */
  private def kindToTypeString(kind: SymbolKind): Option[SymbolType] =
    kind match {
      case SymbolKind.Class => Some(SymbolType.Class)
      case SymbolKind.Interface => Some(SymbolType.Trait)
      case SymbolKind.Object =>
        Some(
          SymbolType.Object // this is probably wrong, scala objects are classes on classfile level
        )
      case SymbolKind.Method => Some(SymbolType.Method)
      case SymbolKind.Function => Some(SymbolType.Function)
      case SymbolKind.Package => Some(SymbolType.Package)
      case _ => None
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
    override def name: String = "unknown"
  }

  val values: List[SymbolType] =
    List(Trait, Package, Class, Object, Function, Method)
}
