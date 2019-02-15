package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.util.control.NonFatal

/**
 * Implements workspace/symbol for both workspace sources and dependency classpath.
 */
final class WorkspaceSymbolProvider(
    val workspace: AbsolutePath,
    statistics: StatisticsConfig,
    val buildTargets: BuildTargets,
    val index: OnDemandSymbolIndex,
    isReferencedPackage: String => Int,
    fileOnDisk: AbsolutePath => AbsolutePath
)(implicit ec: ExecutionContext)
    extends SymbolSearch {
  val inWorkspace = TrieMap.empty[Path, WorkspaceSymbolsIndex]
  var inDependencies = ClasspathSearch.fromClasspath(Nil, isReferencedPackage)

  def search(query: String): Seq[l.SymbolInformation] = {
    search(query, () => ())
  }

  def search(query: String, token: CancelChecker): Seq[l.SymbolInformation] = {
    if (query.isEmpty) return Nil
    try {
      searchUnsafe(query, token)
    } catch {
      case _: CancellationException =>
        Nil
    }
  }

  override def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    search(
      WorkspaceSymbolQuery.exact(query),
      visitor,
      Some(new BuildTargetIdentifier(buildTargetIdentifier))
    )
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
    val bloomFilterStrings =
      Fuzzy.bloomFilterSymbolStrings(symbols.map(_.symbol))
    val bloom = BloomFilter.create[CharSequence](
      Funnels.stringFunnel(StandardCharsets.UTF_8),
      Integer.valueOf(bloomFilterStrings.size),
      0.01
    )
    bloomFilterStrings.foreach { c =>
      bloom.put(c)
    }
    inWorkspace(source.toNIO) = WorkspaceSymbolsIndex(bloom, symbols)
  }

  private def indexClasspathUnsafe(): Unit = {
    val packages = new PackageIndex()
    packages.visitBootClasspath()
    for {
      target <- buildTargets.all
      classpathEntry <- target.scalac.classpath
      if classpathEntry.extension == "jar"
    } {
      packages.visit(classpathEntry)
    }
    inDependencies = ClasspathSearch.fromPackages(
      packages,
      isReferencedPackage
    )
  }

  private def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier]
  ): SymbolSearch.Result = {
    workspaceSearch(query, visitor, target)
    inDependencies.search(query, visitor)
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
    val visitor = new WorkspaceSearchVisitor(query, token, index, fileOnDisk)
    search(query, visitor, None)
    visitor.results.sortBy(_.getName.length)
  }
}

object WorkspaceSymbolProvider {
  def isRelevantKind(kind: Kind): Boolean =
    WorkspaceSymbolQuery.isRelevantKind(kind)
}
