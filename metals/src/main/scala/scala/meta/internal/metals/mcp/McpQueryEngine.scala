package scala.meta.internal.metals.mcp

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta._
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.pc.JavaSourceShortener
import scala.meta.io.AbsolutePath
import scala.meta.pc.ContentType
import scala.meta.pc.ParentSymbols
import scala.meta.transversers.Transformer

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.SymbolKind

/**
 * Query engine for searching symbols in the workspace and classpath.
 * Supports name search, symbol inspection, and documentation retrieval.
 */
class McpQueryEngine(
    compilers: Compilers,
    docstrings: Docstrings,
    buildTargets: BuildTargets,
    referenceProvider: ReferenceProvider,
    scalaVersionSelector: ScalaVersionSelector,
    mcpSearch: McpSymbolSearch,
    workspace: AbsolutePath,
    maxSearchResults: Int,
)(implicit ec: ExecutionContext) {
  import McpQueryEngine.TargetChoice

  private val mcpDefinitionProvider =
    new McpSymbolProvider(scalaVersionSelector, mcpSearch)

  /**
   * Search all modules and every dependency on the workspace classpath for
   * symbols whose last name segment contains `query`, ignoring case.
   *
   * `WorkspaceSymbolQuery` matches candidates at name boundaries, so mid-name
   * matches can be missed; `searchWorkspacePackages` never applies it, so
   * workspace packages match on plain substrings. Wildcards are literal.
   *
   * Results are deduplicated and capped at `maxSearchResults`, keeping the most
   * relevant ones.
   *
   * @param symbolTypes Requested symbol types, empty means all types
   * @return Matching symbols, with flags describing whether matches were dropped
   */
  def globSearch(
      query: String,
      symbolTypes: Set[SymbolType] = Set.empty,
  ): Future[GlobSearchResult] = Future {
    val searchResult = mcpSearch.nameSearch(query, symbolTypes)
    val (results, cappedByResultLimit) = McpQueryEngine.normalize(
      query,
      searchResult.results.map(_.toMcpSymbolSearchResult),
      maxSearchResults,
    )
    GlobSearchResult(
      results = results,
      searchBudgetExhausted = searchResult.searchBudgetExhausted,
      cappedByResultLimit = cappedByResultLimit,
    )
  }

  // Exclude build meta-targets (e.g., root-build) as they don't have project dependencies
  private def isBuildMetaTarget(id: BuildTargetIdentifier): Boolean =
    McpQueryEngine.isBuildMetaTarget(buildTargets, id)

  private def allNonMetaTargets: Seq[BuildTargetIdentifier] =
    buildTargets.allBuildTargetIds.filterNot(isBuildMetaTarget)

  // Find a build target by its display name (module name)
  private def findBuildTargetByName(
      name: String
  ): Option[BuildTargetIdentifier] =
    buildTargets.allBuildTargetIds.find(displayName(_).contains(name))

  private def displayName(id: BuildTargetIdentifier): Option[String] =
    buildTargets.jvmTarget(id).map(_.displayName)

  /**
   * Priority: module > non-meta path target > first non-meta target defining fqcn.
   */
  private def chooseTarget(
      fqcn: String,
      module: Option[String],
      path: Option[AbsolutePath],
  ): TargetChoice = {
    val pathTarget =
      path.flatMap(buildTargets.sourceBuildTargets).flatMap(_.headOption)

    module
      .flatMap(findBuildTargetByName)
      .map(TargetChoice.ByModule(_))
      .orElse(
        pathTarget.filterNot(isBuildMetaTarget).map(TargetChoice.ByFile(_))
      )
      .getOrElse {
        val candidates = allNonMetaTargets match {
          case Seq() => pathTarget.toSeq
          case targets => targets
        }
        val (missing, withSymbol) =
          candidates.span(
            mcpDefinitionProvider.symbolsInTarget(fqcn, _).isEmpty
          )
        // Report tried targets; retain one for context when none match.
        TargetChoice.Guessed(
          withSymbol.headOption.orElse(candidates.headOption),
          missing ++ withSymbol.headOption,
        )
      }
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
  def inspect(
      fqcn: String,
      path: Option[AbsolutePath],
      module: Option[String] = None,
      searchAllTargets: Boolean = false,
  ): Future[InspectResult] = {
    val choice = chooseTarget(fqcn, module, path)
    // An explicit module overrides searchAllTargets.
    val (symbolTargets, searchedTargets) = choice match {
      case TargetChoice.ByModule(id) => (Seq(id), Seq(id))
      case _ if searchAllTargets =>
        val targets = allNonMetaTargets
        (targets, targets)
      case TargetChoice.ByFile(id) => (Seq(id), Seq(id))
      case TargetChoice.Guessed(target, searched) => (target.toSeq, searched)
    }
    val results = for {
      buildTarget <- symbolTargets.toList
      symbol <- mcpDefinitionProvider
        .symbolsInTarget(fqcn, buildTarget)
        .distinctBy(_.symbolType)
    } yield McpInspectProvider.inspect(
      compilers,
      workspace,
      symbol,
      buildTarget,
    )
    Future.sequence(results).map { inspectResults =>
      InspectResult(
        inspectResults.flatten.distinct,
        searchedTargets.flatMap(displayName).toList,
        choice.target.flatMap(displayName),
      )
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
    val syms =
      mcpDefinitionProvider.symbols(
        fqcn,
        chooseTarget(fqcn, module, path).target,
      )
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
    def firstSource(id: BuildTargetIdentifier): Option[AbsolutePath] =
      buildTargets.buildTargetSources(id).headOption

    val choice = chooseTarget(fqcn, module, path)
    val preferred = choice match {
      case TargetChoice.ByModule(id) => firstSource(id).orElse(path)
      case _ => path.orElse(choice.target.flatMap(firstSource))
    }

    // Source-less modules still need a path for reference lookup.
    preferred
      .orElse(allNonMetaTargets.flatMap(firstSource).headOption)
      .map { source =>
        referenceProvider
          .workspaceReferences(
            source,
            mcpDefinitionProvider
              .symbols(fqcn, choice.target)
              .map(_.symbol)
              .toSet,
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
      }
      .getOrElse(Nil)
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

  /**
   * Get the source file contents for a symbol.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @param path Optional path for build target context.
   * @param module Optional module name to use for context. Takes precedence over path.
   * @param detailed If false, bodies are shortened (Scala: ??? and scaladoc removed; Java: empty {}).
   * @return The source file path and its contents, if found
   */
  def getSource(
      fqcn: String,
      path: Option[AbsolutePath] = None,
      module: Option[String] = None,
      detailed: Boolean = false,
  ): Option[(AbsolutePath, String)] = {
    val searchTarget = chooseTarget(fqcn, module, path).target
    val searchResults = mcpSearch.exactSearch(fqcn, searchTarget)

    // For methods and other members, fall back to searching the owner type
    val results =
      if (searchResults.isEmpty && fqcn.lastIndexOf('.') > 0) {
        val owner = fqcn.substring(0, fqcn.lastIndexOf('.'))
        mcpSearch.exactSearch(owner, searchTarget)
      } else {
        searchResults
      }

    results.flatMap(_.definitionPath).headOption.map { defPath =>
      val rawContent = defPath.toInput.text
      val content =
        if (detailed) rawContent
        else shortenSourceBodies(rawContent, defPath)
      (defPath, content)
    }
  }

  /**
   * Shortens source by reducing method/body content: Scala uses ??? for bodies;
   * Java keeps only signatures (empty bodies). Returns the original content on failure.
   */
  private def shortenSourceBodies(
      content: String,
      path: AbsolutePath,
  ): String = {
    val pathStr = path.toString
    if (pathStr.endsWith(".scala")) shortenScalaBodies(content)
    else if (pathStr.endsWith(".java")) shortenJavaBodies(content)
    else content
  }

  /**
   * Replaces every method, val, and var body/rhs with ??? in Scala source.
   * Returns the original content if parsing fails.
   */
  private def shortenScalaBodies(content: String): String = {
    val placeholder = Term.Name("???")
    content.parse[Source] match {
      case Parsed.Success(source) =>
        object shortenBodies extends Transformer {
          override def apply(tree: Tree): Tree = tree match {
            case defn @ Defn.Def(mods, name, tparams, paramss, decltpe, _) =>
              Defn
                .Def(mods, name, tparams, paramss, decltpe, placeholder)
                .fullCopy(
                  begComment = defn.begComment,
                  endComment = defn.endComment,
                )
            case defn @ Defn.Val(mods, pats, decltpe, _) =>
              Defn
                .Val(mods, pats, decltpe, placeholder)
                .fullCopy(
                  begComment = defn.begComment,
                  endComment = defn.endComment,
                )
            case defn @ Defn.Var(mods, pats, decltpe, _) =>
              Defn
                .Var(mods, pats, decltpe, Some(placeholder))
                .fullCopy(
                  begComment = defn.begComment,
                  endComment = defn.endComment,
                )
            case other =>
              super.apply(other)
          }
        }
        shortenBodies.transform(source).toString()
      case _ =>
        content
    }
  }

  /**
   * Replaces every method, constructor, and initializer body in Java source with an empty block,
   * leaving only signatures. Uses the javac Compiler Tree API for accurate parsing.
   * Returns the original content if the source does not parse as valid Java.
   */
  private def shortenJavaBodies(content: String): String =
    JavaSourceShortener.shortenBodies(content).getOrElse(content)
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
    if (results.isEmpty) {
      val searched = if (searchedTargets.nonEmpty) {
        s" (searched targets: ${searchedTargets.mkString(", ")})"
      } else ""
      s"Symbol not found$searched"
    } else {
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

/**
 * Result of a glob search.
 *
 * @param results deduplicated, relevance-capped and display-sorted matches
 * @param searchBudgetExhausted a search layer below stopped early, which does
 *                              not guarantee that more matches exist
 * @param cappedByResultLimit `maxSearchResults` dropped matches
 */
case class GlobSearchResult(
    results: Seq[SymbolSearchResult],
    searchBudgetExhausted: Boolean,
    cappedByResultLimit: Boolean,
)

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

  private sealed trait TargetChoice {
    def target: Option[BuildTargetIdentifier]
  }

  private object TargetChoice {
    case class ByModule(id: BuildTargetIdentifier) extends TargetChoice {
      def target: Option[BuildTargetIdentifier] = Some(id)
    }
    case class ByFile(id: BuildTargetIdentifier) extends TargetChoice {
      def target: Option[BuildTargetIdentifier] = Some(id)
    }
    // Neither module nor file selected a non-meta target.
    case class Guessed(
        target: Option[BuildTargetIdentifier],
        searched: Seq[BuildTargetIdentifier],
    ) extends TargetChoice
  }

  /**
   * Deduplicate, rank, cap and finally display-sort search results.
   *
   * Capping raw order would be nondeterministic, as the indexes below are
   * iterated in an unspecified order. Capping display order would prefer
   * whichever kind sorts first, because `show` starts with the kind. Hence rank
   * by relevance, cap, then sort for display.
   *
   * @return the results to show and whether the cap dropped anything
   */
  private[mcp] def normalize(
      query: String,
      raw: Seq[SymbolSearchResult],
      limit: Int,
  ): (Seq[SymbolSearchResult], Boolean) = {
    def relevance(
        result: SymbolSearchResult
    ): (Int, Int, Int, String, String, String) = {
      val isExactName =
        if (result.name.toLowerCase == query.toLowerCase) 0 else 1
      (
        isExactName,
        result.name.length,
        result.path.count(_ == '.'),
        result.path,
        // picks the same duplicate every time, overloads share kind and path
        result.symbol,
        // makes the order total across everything the dedupe keeps apart
        result.symbolType.name,
      )
    }
    // the rendered form is `kind + path`, so results sharing both would render
    // as duplicate lines and would otherwise consume the cap; ranking first
    // makes the surviving duplicate deterministic
    val ranked = raw
      .sortBy(relevance)
      .distinctBy(result => (result.symbolType, result.path))
    // a cap below 1 would report a partial result with nothing in it
    val shown = ranked.take(limit.max(1))
    (shown.sortBy(_.show), ranked.size > shown.size)
  }

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
