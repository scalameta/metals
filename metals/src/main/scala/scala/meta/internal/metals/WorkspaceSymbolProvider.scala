package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}

/**
 * Implements workspace/symbol for both workspace sources and dependency classpath.
 */
final class WorkspaceSymbolProvider(
    val workspace: AbsolutePath,
    val buildTargets: BuildTargets,
    val index: GlobalSymbolIndex,
    saveClassFileToDisk: Boolean,
    excludedPackageHandler: () => ExcludedPackagesHandler,
    bucketSize: Int = CompressedPackageIndex.DefaultBucketSize,
    classpathSearchIndexer: ClasspathSearch.Indexer =
      ClasspathSearch.Indexer.default,
)(implicit rc: ReportContext) {
  val MaxWorkspaceMatchesForShortQuery = 100
  val inWorkspace: TrieMap[Path, WorkspaceSymbolsIndex] =
    TrieMap.empty[Path, WorkspaceSymbolsIndex]

  // symbols for extension methods
  val inWorkspaceMethods: TrieMap[Path, Seq[WorkspaceSymbolInformation]] =
    TrieMap.empty[Path, Seq[WorkspaceSymbolInformation]]
  var inDependencies: ClasspathSearch =
    ClasspathSearch.empty

  def search(
      query: String,
      fileInFocus: Option[AbsolutePath],
  ): Seq[l.SymbolInformation] = {
    search(query, () => (), fileInFocus)
  }

  def search(
      query: String,
      token: CancelChecker,
      fileInFocus: Option[AbsolutePath],
  ): Seq[l.SymbolInformation] = {
    if (query.isEmpty) return Nil
    try {
      searchUnsafe(query, token, fileInFocus)
    } catch {
      case InterruptException() =>
        Nil
    }
  }

  def searchExactFrom(
      queryString: String,
      path: AbsolutePath,
      token: CancelToken,
      fileInFocus: Option[AbsolutePath],
  ): Seq[l.SymbolInformation] = {
    val query = WorkspaceSymbolQuery.exact(queryString)
    val visitor =
      new WorkspaceSearchVisitor(
        workspace,
        query,
        token,
        index,
        saveClassFileToDisk,
        SymbolDefinitionOrdering.fromOptPath(fileInFocus),
      )
    val targetId = buildTargets.inverseSources(path)
    search(query, visitor, targetId)
    visitor.allResults().filter(_.getName() == queryString)
  }

  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier],
  ): (SymbolSearch.Result, Int) = {
    val workspaceCount = workspaceSearch(query, visitor, target)
    val (res, inDepsCount) = inDependencies.search(query, visitor)
    (res, workspaceCount + inDepsCount)
  }

  def searchMethods(
      query: String,
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier],
  ): SymbolSearch.Result = {
    workspaceMethodSearch(query, visitor, target)
    SymbolSearch.Result.COMPLETE
  }

  def indexClasspath(): Unit = {
    try {
      indexClasspathUnsafe()
    } catch {
      case NonFatal(e) =>
        scribe.error("failed to index classpath for workspace/symbol", e)
    }
  }

  def didRemove(path: AbsolutePath): Unit = {
    inWorkspace.remove(path.toNIO)
  }

  def didChange(
      source: AbsolutePath,
      symbols: Seq[WorkspaceSymbolInformation],
      methodSymbols: Seq[WorkspaceSymbolInformation],
  ): Unit = {
    val bloom = Fuzzy.bloomFilterSymbolStrings(symbols.map(_.symbol))
    inWorkspace(source.toNIO) = WorkspaceSymbolsIndex(bloom, symbols)

    // methodSymbols will be searched when we type `qual.x@@`
    // where we want to match by prefix-match query.
    // Do not index by bloom filter for (extension) method symbols here because
    // - currently, we don't index each prefix of the name to bloom filter, so we can't find `incr` by `i`
    //   if we index it by bloom filter and lookup against it.
    // - symbol search will take O(N), if we don't use bloom filter, but
    //   inWorkspaceMethods stores extension methods only, and the number of symbols (N) are quite limited.
    //   Therefore, we can expect symbol lookup for extension methods could be fast enough without bloom-filter.
    if (methodSymbols.nonEmpty)
      inWorkspaceMethods(source.toNIO) = methodSymbols
  }

  def buildTargetSymbols(
      id: BuildTargetIdentifier
  ): Iterator[WorkspaceSymbolInformation] = {
    for {
      source <- buildTargets.buildTargetSources(id).iterator
      index <- inWorkspace.get(source.toNIO).iterator
      sym <- index.symbols.iterator
    } yield sym
  }

  private def indexClasspathUnsafe(): Unit = {
    val jars = buildTargets.allWorkspaceJars.map(_.toNIO).toSeq
    inDependencies = classpathSearchIndexer.index(
      jars,
      excludedPackageHandler(),
      bucketSize,
    )
    scribe.info(s"indexed ${jars.size} workspace jars")
  }

  private def workspaceMethodSearch(
      query: String,
      visitor: SymbolSearchVisitor,
      id: Option[BuildTargetIdentifier],
  ): Unit = {
    for {
      (path, symbols) <- id match {
        case None =>
          inWorkspaceMethods.iterator
        case Some(target) =>
          for {
            source <- buildTargets.buildTargetTransitiveSources(target)
            symbols <- inWorkspaceMethods.get(source.toNIO)
          } yield (source.toNIO, symbols)
      }
      isDeleted = !Files.isRegularFile(path)
      _ = if (isDeleted) inWorkspaceMethods.remove(path)
      if !isDeleted
      symbol <- symbols
      if Fuzzy.matches(query, symbol.symbol)
    }
      visitor.visitWorkspaceSymbol(
        path,
        symbol.symbol,
        symbol.kind,
        symbol.range,
      )
  }

  private def workspaceSearch(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      id: Option[BuildTargetIdentifier],
  ): Int = {
    val symbols = for {
      (path, index) <- id match {
        case None =>
          inWorkspace.iterator
        case Some(target) =>
          for {
            source <- buildTargets.buildTargetTransitiveSources(target)
            index <- inWorkspace.get(source.toNIO)
          } yield (source.toNIO, index)
      }
      if query.matches(index.bloom)
      isDeleted = !Files.isRegularFile(path)
      _ = if (isDeleted) inWorkspace.remove(path)
      if !isDeleted
      symbol <- index.symbols
      if query.matches(symbol.symbol)
    } yield (path, symbol)

    @tailrec
    def loopSearch(count: Int): Int =
      if (
        !symbols.hasNext || (query.isShortQuery && count >= MaxWorkspaceMatchesForShortQuery)
      ) count
      else {
        val (path, symbol) = symbols.next()
        val added = visitor.visitWorkspaceSymbol(
          path,
          symbol.symbol,
          symbol.kind,
          symbol.range,
        )
        loopSearch(count + added)
      }

    loopSearch(0)
  }

  private def searchUnsafe(
      textQuery: String,
      token: CancelChecker,
      fileInFocus: Option[AbsolutePath],
  ): Seq[l.SymbolInformation] = {
    val query = WorkspaceSymbolQuery.fuzzy(textQuery)
    val visitor =
      new WorkspaceSearchVisitor(
        workspace,
        query,
        token,
        index,
        saveClassFileToDisk,
        SymbolDefinitionOrdering.fromOptPath(fileInFocus),
      )
    search(query, visitor, None)
    visitor.allResults()
  }

  class PreferredScalaVersionOrdering(preferredScalaVersions: Set[String])
      extends Ordering[SymbolDefinition] {
    private def pathMatchesPreferred(path: AbsolutePath) =
      buildTargets
        .possibleScalaVersions(path)
        .exists(preferredScalaVersions(_))

    private def pathLength(symbolDef: SymbolDefinition) =
      symbolDef.path.toURI.toString().length()

    override def compare(x: SymbolDefinition, y: SymbolDefinition): Int = {
      val xVersionMatches = pathMatchesPreferred(x.path)
      val yVersionMatches = pathMatchesPreferred(y.path)

      if (xVersionMatches && !yVersionMatches) -1
      else if (yVersionMatches && !xVersionMatches) 1
      else pathLength(x) - pathLength(y)
    }
  }

  object SymbolDefinitionOrdering {
    def fromOptPath(path: Option[AbsolutePath]): Ordering[SymbolDefinition] = {
      path.toList.flatMap(buildTargets.possibleScalaVersions(_)) match {
        case Nil => DefaultSymbolDefinitionOrdering
        case preferredScalaVersions =>
          new PreferredScalaVersionOrdering(preferredScalaVersions.toSet)
      }
    }
  }
}
