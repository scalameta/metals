package scala.meta.internal.query

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.metals.docstrings.query
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.pc.ContentType
import scala.meta.pc.InspectResult
import scala.meta.pc.ParentSymbols

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.SymbolKind

/**
 * Query engine for searching symbols in the workspace and classpath.
 * Supports glob search, symbol inspection, and documentation retrieval.
 *
 * @param workspaceSearchProvider Provider for workspace symbol search
 */
class QueryEngine(
    workspaceSearchProvider: WorkspaceSymbolProvider,
    focusedDocumentBuildTarget: () => Option[BuildTargetIdentifier],
    index: GlobalSymbolIndex,
    compilers: Compilers,
    docstrings: Docstrings,
)(implicit ec: ExecutionContext) {

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
    workspaceSearchProvider.search(
      wsQuery,
      visitor,
      focusedDocumentBuildTarget(),
    )
    workspaceSearchProvider.searchWorkspacePackages(
      visitor,
      focusedDocumentBuildTarget(),
    )

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
  ): Future[List[SymbolInspectResult]] = {

    // Implementation would need to:
    // 1. Find the symbol definition
    // 2. Extract information based on symbol type
    // 3. If deep=true, fetch nested information in multiple queries in parallel up to 3 levels deep

    // This is a placeholder - the actual implementation would
    // use the workspace provider and index to resolve the symbol
    // and extract detailed information about its members

    focusedDocumentBuildTarget()
      .map { buildTarget =>
        compilers.inspect(buildTarget, fqcn).map {
          _.flatMap { res =>
            val (constructorMembers, otherMembers) = res
              .members()
              .asScala
              .toList
              .partition(_.kind() == SymbolKind.Constructor)
            val members =
              otherMembers.flatMap(res => mapPcInspectResult(res, Nil, Nil))
            val constructors = constructorMembers
              .flatMap(mapPcInspectResult(_, Nil, Nil))
              .collect { case m: MethodInspectResult => m }
            mapPcInspectResult(res, members, constructors)
          }
        }
      }
      .getOrElse(Future.successful(Nil))
  }

  private def mapPcInspectResult(
      result: InspectResult,
      members: List[SymbolInspectResult],
      constructors: List[MethodInspectResult],
  ) = {
    val kind = QueryEngine
      .kindToTypeString(result.kind())
      .getOrElse(SymbolType.Unknown(result.kind().toString))
    val path = result.symbol().fqcn
    kind match {
      case SymbolType.Package => Some(PackageInspectResult(path, members))
      case SymbolType.Class =>
        Some(ClassInspectResult(path, members, constructors))
      case SymbolType.Object => Some(ObjectInspectResult(path, members))
      case SymbolType.Trait => Some(TraitInspectResult(path, members))
      case SymbolType.Method | SymbolType.Function | SymbolType.Constructor =>
        Some(
          MethodInspectResult(
            path = path,
            returnType = result.resultType(),
            parameters = result.paramss().asScala.toList.map {
              case paramList if paramList.isType() == java.lang.Boolean.TRUE =>
                TypedParamList(paramList.params().asScala.toList)
              case paramList =>
                TermParamList(
                  paramList.params().asScala.toList,
                  paramList.implicitOrUsingKeyword(),
                )

            },
            visibility = result.visibility(),
            symbolType = kind,
          )
        )
      case _ => None
    }
  }

  /**
   * Retrieve documentation for a specific symbol.
   *
   * @param fqcn Fully qualified class name (or symbol)
   * @return Documentation for the symbol
   */
  def getDocumentation(
      fqcn: String
  ): Future[Option[query.SymbolDocumentation]] = {

    // Implementation would need to:
    // 1. Find the symbol definition
    // 2. Extract the Scaladoc information

    // This is a placeholder - the actual implementation would
    // parse Scaladoc from the source file
    focusedDocumentBuildTarget()
      .map { bt =>
        compilers.symbols(bt, fqcn).map { syms =>
          syms.iterator
            .flatMap(s =>
              docstrings
                .documentation(
                  s,
                  QueryEngine.emptyParentsSymbols,
                  ContentType.QUERY,
                )
                .asScala
            )
            .collectFirst { case doc: query.SymbolDocumentation => doc }
        }
      }
      .getOrElse(Future.successful(None))
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

trait TemplateInspectResult extends SymbolInspectResult {
  def members: List[SymbolInspectResult]
}

case class ObjectInspectResult(
    override val path: String,
    override val members: List[SymbolInspectResult],
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Object
}

case class TraitInspectResult(
    override val path: String,
    override val members: List[SymbolInspectResult],
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Trait
}

case class ClassInspectResult(
    override val path: String,
    override val members: List[SymbolInspectResult],
    val constructors: List[MethodInspectResult],
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Class
}

case class MethodInspectResult(
    override val path: String,
    returnType: String,
    parameters: List[ParamList],
    visibility: String,
    override val symbolType: SymbolType,
) extends SymbolInspectResult

sealed trait ParamList
case class TypedParamList(params: List[String]) extends ParamList
case class TermParamList(params: List[String], prefix: String) extends ParamList

case class PackageInspectResult(
    override val path: String,
    override val members: List[SymbolInspectResult],
) extends TemplateInspectResult {
  override val symbolType: SymbolType = SymbolType.Package
}

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

object QueryEngine {

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
      case SymbolKind.Package => Some(SymbolType.Package)
      case SymbolKind.Constructor => Some(SymbolType.Constructor)
      case _ => None
    }

  val emptyParentsSymbols: ParentSymbols = new ParentSymbols {
    override def parents(): ju.List[String] = Nil.asJava
  }
}
