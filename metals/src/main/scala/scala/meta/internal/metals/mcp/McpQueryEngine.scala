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
import scala.meta.io.AbsolutePath
import scala.meta.pc.ContentType
import scala.meta.pc.ParentSymbols

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
   * @param path Path to the file in context
   * @param provideMethodSignatures Whether to provide signatures for method members
   * @return Information about the symbol
   */
  def inspect(
      fqcn: String,
      path: AbsolutePath,
  ): Future[List[SymbolInspectResult]] = {
    val results = for {
      buildTarget <- buildTargets
        .sourceBuildTargets(path)
        .flatMap(_.headOption)
        .toList
      symbol <- mcpDefinitionProvider
        .symbols(fqcn, Some(path))
        .distinctBy(_.symbolType)
    } yield McpInspectProvider.inspect(
      compilers,
      workspace,
      symbol,
      buildTarget,
    )

    Future.sequence(results).map(_.flatten)
  }

  /**
   * Retrieve documentation for a specific symbol.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @return Documentation for the symbol
   */
  def getDocumentation(
      fqcn: String
  ): Option[SymbolDocumentationSearchResult] = {
    val syms = mcpDefinitionProvider.symbols(fqcn, None)
    val res = syms.iterator.flatMap { s =>
      docstrings
        .documentation(
          s.symbol,
          McpQueryEngine.emptyParentsSymbols,
          ContentType.PLAINTEXT,
        )
        .asScala
        .map { doc =>
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

  def getUsages(
      fqcn: String,
      path: AbsolutePath,
  ): List[SymbolUsage] = {
    val syms = mcpDefinitionProvider.symbols(fqcn, Some(path))
    referenceProvider
      .workspaceReferences(
        path,
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
  }

}

case class SymbolUsage(
    path: AbsolutePath,
    line: Int,
)

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
