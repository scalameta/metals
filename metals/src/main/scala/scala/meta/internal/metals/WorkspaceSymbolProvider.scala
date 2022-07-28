package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.io.AbsolutePath
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
) {
  val inWorkspace: TrieMap[Path, WorkspaceSymbolsIndex] =
    TrieMap.empty[Path, WorkspaceSymbolsIndex]

  // symbols for extension methods
  val inWorkspaceMethods: TrieMap[Path, Seq[WorkspaceSymbolInformation]] =
    TrieMap.empty[Path, Seq[WorkspaceSymbolInformation]]
  var inDependencies: ClasspathSearch =
    ClasspathSearch.empty

  def search(query: String): Seq[l.SymbolInformation] = {
    search(query, () => ())
  }

  def search(query: String, token: CancelChecker): Seq[l.SymbolInformation] = {
    if (query.isEmpty) return Nil
    try {
      searchUnsafe(query, token)
    } catch {
      case InterruptException() =>
        Nil
    }
  }

  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier],
  ): SymbolSearch.Result = {
    workspaceSearch(query, visitor, target)
    inDependencies.search(query, visitor)
  }

  def searchMethods(
      query: String,
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier],
  ): SymbolSearch.Result = {
    workspaceMethodSearch(query, visitor, target)
    if (query.isEmpty()) SymbolSearch.Result.INCOMPLETE
    else SymbolSearch.Result.COMPLETE
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
    val jars = buildTargets.allWorkspaceJars
    inDependencies = classpathSearchIndexer.index(
      jars.map(_.toNIO).toSeq,
      excludedPackageHandler(),
      bucketSize,
    )
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
  ): Unit = {
    for {
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
    } {
      visitor.visitWorkspaceSymbol(
        path,
        symbol.symbol,
        symbol.kind,
        symbol.range,
      )
    }
  }

  private def searchUnsafe(
      textQuery: String,
      token: CancelChecker,
  ): Seq[l.SymbolInformation] = {
    val query = WorkspaceSymbolQuery.fromTextQuery(textQuery)
    val visitor =
      new WorkspaceSearchVisitor(
        workspace,
        query,
        token,
        index,
        saveClassFileToDisk,
      )
    search(query, visitor, None)
    visitor.allResults()
  }
}

object WorkspaceSymbolProvider {
  def isRelevantKind(kind: Kind): Boolean =
    WorkspaceSymbolQuery.isRelevantKind(kind)
}
