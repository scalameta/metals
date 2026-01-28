package scala.meta.internal.metals.mcp

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.io.AbsolutePath
import scala.meta.pc.ContentType
import scala.meta.pc.ParentSymbols

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.SymbolKind

/**
 * Query engine for searching symbols in the workspace and classpath.
 * Supports glob search, symbol inspection, and documentation retrieval.
 *
 * @param workspaceSearchProvider Provider for workspace symbol search
 */
class McpQueryEngine(
    compilers: Compilers,
    docstrings: Docstrings,
    buildTargets: BuildTargets,
    referenceProvider: ReferenceProvider,
    scalaVersionSelector: ScalaVersionSelector,
    mcpSearch: McpSymbolSearch,
    workspace: AbsolutePath,
)(implicit ec: ExecutionContext) {
  private val mcpDefinitionProvider =
    new McpSymbolProvider(scalaVersionSelector, mcpSearch)

  /**
   * Search for symbols matching a glob pattern.
   *
   * @param query The search query (e.g., "matching" will search for "*matching*")
   * @param symbolTypes Set of symbol types to filter results (empty means all types)
   * @param path Path to the file in context
   * @return Collection of matching symbols with their information
   */
  def globSearch(
      query: String,
      symbolTypes: Set[SymbolType] = Set.empty,
      path: AbsolutePath,
  ): Future[Seq[SymbolSearchResult]] = Future {
    mcpSearch
      .nameSearch(
        query,
        symbolTypes,
        Some(path),
      )
      .map(_.toMcpSymbolSearchResult)
  }

  /**
   * Inspect a specific symbol to get detailed information.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @param path Optional path to the file in context for build target resolution
   * @param module Optional module name to use for context. Takes precedence over path.
   * @param searchAllTargets If true, search all build targets and combine results
   * @return Information about the symbol
   */
  // Exclude build meta-targets (e.g., root-build) as they don't have project dependencies
  private def isBuildMetaTarget(id: BuildTargetIdentifier): Boolean =
    McpQueryEngine.isBuildMetaTarget(buildTargets, id)

  // Find a build target by its display name (module name)
  private def findBuildTargetByName(
      name: String
  ): Option[BuildTargetIdentifier] = {
    buildTargets.allBuildTargetIds.find { id =>
      buildTargets.jvmTarget(id).exists(_.displayName == name)
    }
  }

  /**
   * Resolve the effective path for build target context.
   * Priority: module > path > smart fallback (first available non-meta build target)
   */
  private def resolveEffectivePath(
      module: Option[String],
      path: Option[AbsolutePath],
  ): Option[AbsolutePath] = {
    // Check if path's build target is a meta-target
    val pathTargetIsMeta = path
      .flatMap(buildTargets.sourceBuildTargets)
      .flatMap(_.headOption)
      .exists(isBuildMetaTarget)

    // Non-meta path (use directly if path's target isn't meta)
    val nonMetaPath = path.filterNot(_ => pathTargetIsMeta)

    module
      .flatMap(findBuildTargetByName)
      .flatMap { targetId =>
        buildTargets.buildTargetSources(targetId).headOption
      }
      .orElse(nonMetaPath)
      .orElse {
        // Smart fallback: use first available source file from non-meta build target
        buildTargets.allBuildTargetIds
          .filterNot(isBuildMetaTarget)
          .headOption
          .flatMap { targetId =>
            buildTargets.buildTargetSources(targetId).headOption
          }
      }
      .orElse(path) // Final fallback: original path (even if meta-target)
  }

  def inspect(
      fqcn: String,
      path: Option[AbsolutePath],
      module: Option[String] = None,
      searchAllTargets: Boolean = false,
  ): Future[InspectResult] = {
    // Determine which build targets to use
    // Priority: module > path > smart fallback
    val targetBuildTargets: Seq[BuildTargetIdentifier] =
      module.flatMap(findBuildTargetByName) match {
        case Some(bt) =>
          // Explicit module name provided - use that build target
          Seq(bt)
        case None =>
          // Get path's build target (if path provided)
          val pathTarget = path
            .flatMap(buildTargets.sourceBuildTargets)
            .flatMap(_.headOption)

          if (searchAllTargets) {
            // When searching all targets, exclude meta-targets
            buildTargets.allBuildTargetIds.filterNot(isBuildMetaTarget)
          } else {
            // Single target: prefer non-meta path target, then non-meta fallback, then meta path target
            pathTarget
              .filterNot(isBuildMetaTarget)
              .orElse {
                buildTargets.allBuildTargetIds
                  .filterNot(isBuildMetaTarget)
                  .headOption
              }
              .orElse(pathTarget)
              .toSeq
          }
      }

    if (targetBuildTargets.isEmpty) {
      Future.successful(InspectResult(Nil, Nil, None))
    } else {
      val results = for {
        buildTarget <- targetBuildTargets.toList
        symbol <- mcpDefinitionProvider
          .symbols(fqcn, path)
          .distinctBy(_.symbolType)
      } yield McpInspectProvider.inspect(
        compilers,
        workspace,
        symbol,
        buildTarget,
      )

      Future.sequence(results).map { inspectResults =>
        val flattened = inspectResults.flatten
        // Deduplicate results that are identical across targets
        val deduplicated = flattened.distinct
        // Collect which targets were searched
        val searchedTargets = targetBuildTargets
          .flatMap(bt => buildTargets.jvmTarget(bt).map(_.displayName))
          .toList
        // Determine the primary target used
        // Priority: explicit module > non-meta path target > first searched target
        // If module name was provided, use it directly as primaryTarget
        // Otherwise derive displayName from path's build target (if non-meta) or first searched target
        val pathTarget = path
          .flatMap(p =>
            buildTargets.sourceBuildTargets(p).flatMap(_.headOption)
          )
        val primaryTarget = module.orElse {
          pathTarget
            .filterNot(isBuildMetaTarget)
            .orElse(targetBuildTargets.headOption)
            .flatMap(bt => buildTargets.jvmTarget(bt).map(_.displayName))
        }

        InspectResult(deduplicated, searchedTargets, primaryTarget)
      }
    }
  }

  /**
   * Legacy inspect method for backward compatibility.
   * Delegates to the new method with explicit path.
   */
  def inspect(
      fqcn: String,
      path: AbsolutePath,
  ): Future[List[SymbolInspectResult]] = {
    inspect(fqcn, Some(path), module = None, searchAllTargets = false)
      .map(_.results)
  }

  /**
   * Retrieve documentation for a specific symbol.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @param path Optional path for build target context. If not provided,
   *             uses first available build target as fallback.
   * @param module Optional module name to use for context. Takes precedence over path.
   * @return Documentation for the symbol
   */
  def getDocumentation(
      fqcn: String,
      path: Option[AbsolutePath] = None,
      module: Option[String] = None,
  ): Option[SymbolDocumentationSearchResult] = {
    val effectivePath = resolveEffectivePath(module, path)
    val syms = mcpDefinitionProvider.symbols(fqcn, effectivePath)
    val res = syms.iterator.flatMap { s =>
      val docOpt = docstrings
        .documentation(
          s.symbol,
          McpQueryEngine.emptyParentsSymbols,
          ContentType.PLAINTEXT,
        )
        .asScala
      docOpt.filter(_.docstring().nonEmpty).map { doc =>
        SymbolDocumentationSearchResult(
          s.symbolType,
          Option(doc.docstring()),
          s.path,
        )
      }
    }.headOption

    res.orElse {
      // when no documentation is found, return the first symbol
      syms.headOption.map { s =>
        SymbolDocumentationSearchResult(
          s.symbolType,
          None,
          s.path,
        )
      }
    }
  }

  /**
   * Find all usages of a symbol across the workspace.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @param path Optional path for build target context. If not provided,
   *             uses first available build target as fallback.
   * @param module Optional module name to use for context. Takes precedence over path.
   * @return List of symbol usages with file paths and line numbers
   */
  def getUsages(
      fqcn: String,
      path: Option[AbsolutePath] = None,
      module: Option[String] = None,
  ): List[SymbolUsage] = {
    val effectivePath = resolveEffectivePath(module, path)

    effectivePath match {
      case Some(p) =>
        val syms = mcpDefinitionProvider.symbols(fqcn, Some(p))
        referenceProvider
          .workspaceReferences(
            p,
            syms.map(_.symbol).toSet,
            isIncludeDeclaration = true,
            includeSynthetics = _ => false,
          )
          .map { usage =>
            SymbolUsage(
              usage.getUri.toAbsolutePath,
              usage.getRange.getStart.getLine + 1, // +1 because line is 0-indexed
            )
          }
          .toList
      case None =>
        Nil
    }
  }

  /**
   * Legacy getUsages method for backward compatibility.
   * Delegates to the new method with optional path.
   */
  def getUsages(
      fqcn: String,
      path: AbsolutePath,
  ): List[SymbolUsage] = {
    getUsages(fqcn, Some(path), module = None)
  }

}

case class SymbolUsage(
    path: AbsolutePath,
    line: Int,
)

/**
 * Result of an inspect operation with metadata about searched targets.
 *
 * @param results The inspection results (deduplicated across targets)
 * @param searchedTargets Names of build targets that were searched
 * @param primaryTarget The primary target used (from fileInFocus or first available)
 */
case class InspectResult(
    results: List[SymbolInspectResult],
    searchedTargets: List[String],
    primaryTarget: Option[String],
) {
  def show: String = {
    val resultsStr = results.map(_.show).mkString("\n")
    val targetInfo = primaryTarget match {
      case Some(target) if searchedTargets.size > 1 =>
        s"\n\n[Inspected from '$target' module. Also searched: ${searchedTargets.filterNot(_ == target).mkString(", ")}]"
      case Some(target) =>
        s"\n\n[Inspected from '$target' module]"
      case None if searchedTargets.nonEmpty =>
        s"\n\n[Searched targets: ${searchedTargets.mkString(", ")}]"
      case None =>
        ""
    }
    resultsStr + targetInfo
  }
}

/**
 * Base trait for symbol search results.
 */
case class SymbolSearchResult(
    path: String,
    symbolType: SymbolType,
    symbol: String,
) {
  def name: String = path.split('.').lastOption.getOrElse(path)
}

case class MethodSignature(
    name: String
)

/**
 * Base trait for inspection results.
 */
sealed trait SymbolInspectResult {
  def name: String = path.split('.').last
  def path: String
  def symbolType: SymbolType
}

sealed trait TemplateInspectResult extends SymbolInspectResult {
  def members: List[String]
}

case class ObjectInspectResult(
    override val path: String,
    override val members: List[String],
    val auxilaryContext: String,
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Object
}

case class TraitInspectResult(
    override val path: String,
    override val members: List[String],
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Trait
}

case class ClassInspectResult(
    override val path: String,
    override val members: List[String],
    val constructors: List[String],
    val auxilaryContext: String,
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Class
}

case class MethodInspectResult(
    override val path: String,
    signatures: List[String],
    override val symbolType: SymbolType,
) extends SymbolInspectResult

sealed trait ParamList
case class TypedParamList(params: List[String]) extends ParamList
case class TermParamList(params: List[String], prefix: String) extends ParamList

case class PackageInspectResult(
    override val path: String,
    override val members: List[String],
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Package
}

/**
 * Result for documentation query.
 */
case class SymbolDocumentationSearchResult(
    val symbolType: SymbolType,
    val documentation: Option[String],
    val path: String,
) extends SymbolInspectResult

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

  case object Constructor extends SymbolType {
    override def name: String = "constructor"
  }

  case class Unknown(kind: String) extends SymbolType {
    override def name: String = kind
  }

  val values: List[SymbolType] =
    List(Trait, Package, Class, Object, Function, Method)
}

object McpQueryEngine {

  /**
   * Check if a build target is a meta-target (e.g., SBT build definition).
   * Meta-targets like "root-build" don't have project dependencies and should
   * be excluded from smart fallback selection.
   */
  def isBuildMetaTarget(
      buildTargets: BuildTargets,
      id: BuildTargetIdentifier,
  ): Boolean = {
    buildTargets.info(id).exists(_.isSbtBuild)
  }

  /**
   * Convert SymbolKind to a string representation.
   */
  def kindToTypeString(kind: SymbolKind): Option[SymbolType] =
    kind match {
      case SymbolKind.Class => Some(SymbolType.Class)
      case SymbolKind.Interface => Some(SymbolType.Trait)
      case SymbolKind.Object => Some(SymbolType.Object)
      case SymbolKind.Method => Some(SymbolType.Method)
      case SymbolKind.Function => Some(SymbolType.Function)
      case SymbolKind.Package | SymbolKind.Module => Some(SymbolType.Package)
      case SymbolKind.Constructor => Some(SymbolType.Constructor)
      case _ => None
    }

  val emptyParentsSymbols: ParentSymbols = new ParentSymbols {
    override def parents(): ju.List[String] = Nil.asJava
  }

  val uninterestingCompletions: Set[String] = Set(
    "asInstanceOf[T0]: T0", "equals(x$1: Object): Boolean",
    "getClass(): Class[_ <: Object]", "hashCode(): Int",
    "isInstanceOf[T0]: Boolean", "synchronized[T0](x$1: T0): T0",
    "toString(): String", "+(other: String): String",
  )
}
