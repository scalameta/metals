package scala.meta.internal.metals

import java.net.URI
import java.util.Optional
import java.{util => ju}

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.io.AbsolutePath
import scala.meta.pc.ContentType
import scala.meta.pc.ParentSymbols
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Location

/**
 * Implementation of SymbolSearch that delegates to WorkspaceSymbolProvider and SymbolDocumentationIndexer.
 */
class MetalsSymbolSearch(
    docs: Docstrings,
    wsp: WorkspaceSymbolProvider,
    defn: DefinitionProvider,
)(implicit rc: ReportContext)
    extends SymbolSearch {
  // A cache for definitionSourceToplevels.
  // The key is an absolute path to the dependency source file, and
  // the value is the list of symbols that the file contains.
  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()

  def reset(): Unit = {
    dependencySourceCache.clear()
  }

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
  ): Optional[SymbolDocumentation] =
    documentation(symbol, parents, ContentType.MARKDOWN)

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
      contentType: ContentType,
  ): Optional[SymbolDocumentation] =
    docs.documentation(symbol, parents, contentType)

  def definition(symbol: String, source: URI): ju.List[Location] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    defn.fromSymbol(symbol, sourcePath)
  }

  /**
   * Returns a list of semanticdb symbols in a source file that contains the
   * definition of the given symbol.
   */
  override def definitionSourceToplevels(
      symbol: String,
      source: URI,
  ): ju.List[String] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    defn
      .definitionPathInputFromSymbol(symbol, sourcePath)
      .map(input => {
        val pathOpt = input.path.toAbsolutePathSafe
        pathOpt match {
          case None =>
            ju.Collections.emptyList[String]()
          case Some(path) =>
            if (path.isWorkspaceSource(wsp.workspace)) {
              // If the source file is a workspace source, retrieve its symbols from
              // WorkspaceSymbolProvider so that metals server can reuse its cache.
              wsp.inWorkspace
                .get(path.toNIO)
                .map(symInfo => {
                  symInfo.symbols
                    .sortBy(sym =>
                      (
                        sym.range.getStart().getLine(),
                        sym.range.getStart().getCharacter(),
                      )
                    )
                    .map(_.symbol)
                    .asJava
                })
                .getOrElse(
                  ju.Collections.emptyList[String]()
                )
            } else {
              dependencySourceCache.getOrElseUpdate(
                path,
                Mtags.topLevelSymbols(path).asJava,
              )
            }
        }
      })
      .getOrElse(ju.Collections.emptyList())
  }

  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    def search(query: WorkspaceSymbolQuery) =
      wsp.search(
        query,
        visitor,
        Some(new BuildTargetIdentifier(buildTargetIdentifier)),
      )

    val wQuery = WorkspaceSymbolQuery.exact(query)
    val (result, count) = search(wQuery)
    if (wQuery.isShortQuery && count == 0)
      search(WorkspaceSymbolQuery.exact(query, isShortQueryRetry = true))._1
    else result
  }

  override def searchMethods(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    wsp.searchMethods(
      query,
      visitor,
      Some(new BuildTargetIdentifier(buildTargetIdentifier)),
    )
  }

  override def queryImplicitClassMembers(
      paramTypeSymbol: String
  ): ju.List[scala.meta.pc.ImplicitClassMemberResult] = {
    import scala.meta.internal.jdk.CollectionConverters._

    scribe.info(
      s"[MetalsSymbolSearch] Querying implicit class members for type: $paramTypeSymbol"
    )

    val totalCached = wsp.implicitClassMembers.values.flatten.size
    scribe.info(
      s"[MetalsSymbolSearch] Total implicit class members in cache: $totalCached"
    )

    val results = List.newBuilder[scala.meta.pc.ImplicitClassMemberResult]

    // Extract the simple type name for flexible matching
    // Handles both "scala/Int#" from queries and "Int#" from indexed data
    val simpleTypeName = paramTypeSymbol.split("/").last

    wsp.implicitClassMembers.values.flatten.foreach { member =>
      // Match if either:
      // 1. Exact match (e.g., both are "scala/Int#")
      // 2. Simple name match (e.g., query "scala/Int#" matches indexed "Int#")
      val memberSimpleName = member.paramType.split("/").last
      if (
        member.paramType == paramTypeSymbol || memberSimpleName == simpleTypeName
      ) {
        scribe.debug(
          s"[MetalsSymbolSearch]   Match found: ${member.methodName} from ${member.classSymbol} " +
            s"(query: $paramTypeSymbol, indexed: ${member.paramType})"
        )
        results += new scala.meta.pc.ImplicitClassMemberResult(
          member.methodSymbol,
          member.methodName,
          member.classSymbol,
        )
      }
    }

    val resultList = results.result()
    if (resultList.nonEmpty) {
      scribe.info(
        s"[MetalsSymbolSearch] Found ${resultList.size} implicit class members for $paramTypeSymbol"
      )
    } else {
      scribe.debug(
        s"[MetalsSymbolSearch] No implicit class members found for $paramTypeSymbol"
      )
    }

    resultList.asJava
  }

  def workspaceSymbols(): WorkspaceSymbolProvider = wsp
}
