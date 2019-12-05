package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.nio.file.Path
import java.nio.file.Files
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.util.control.NonFatal
import scala.meta.internal.pc.InterruptException

/**
 * Implements workspace/symbol for both workspace sources and dependency classpath.
 */
final class WorkspaceSymbolProvider(
    val workspace: AbsolutePath,
    statistics: StatisticsConfig,
    val buildTargets: BuildTargets,
    val index: OnDemandSymbolIndex,
    fileOnDisk: AbsolutePath => AbsolutePath,
    bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
)(implicit ec: ExecutionContext) {
  val inWorkspace: TrieMap[Path, WorkspaceSymbolsIndex] =
    TrieMap.empty[Path, WorkspaceSymbolsIndex]
  var inDependencies: ClasspathSearch =
    ClasspathSearch.fromClasspath(Nil, bucketSize)

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
      target: Option[BuildTargetIdentifier]
  ): SymbolSearch.Result = {
    workspaceSearch(query, visitor, target)
    inDependencies.search(query, visitor)
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
      symbols: Seq[WorkspaceSymbolInformation]
  ): Unit = {
    val bloom = Fuzzy.bloomFilterSymbolStrings(symbols.map(_.symbol))
    inWorkspace(source.toNIO) = WorkspaceSymbolsIndex(bloom, symbols)
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
    val packages = new PackageIndex()
    packages.visitBootClasspath()
    for {
      classpathEntry <- buildTargets.allWorkspaceJars
    } {
      packages.visit(classpathEntry)
    }
    inDependencies = ClasspathSearch.fromPackages(packages, bucketSize)
  }

  private def workspaceSearch(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      id: Option[BuildTargetIdentifier]
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
        symbol.range
      )
    }
  }

  private def searchUnsafe(
      textQuery: String,
      token: CancelChecker
  ): Seq[l.SymbolInformation] = {
    val query = WorkspaceSymbolQuery.fromTextQuery(textQuery)
    val visitor =
      new WorkspaceSearchVisitor(workspace, query, token, index, fileOnDisk)
    search(query, visitor, None)
    visitor.allResults()
  }

}

object WorkspaceSymbolProvider {
  def isRelevantKind(kind: Kind): Boolean =
    WorkspaceSymbolQuery.isRelevantKind(kind)
}
