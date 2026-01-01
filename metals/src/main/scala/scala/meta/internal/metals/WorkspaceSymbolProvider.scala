package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.ToplevelMember
import scala.meta.internal.mtags.ToplevelMember.Kind._
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.pc.MemberKind
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

  val packages: TrieMap[BuildTargetIdentifier, TrieMap[String, PackageNode]] =
    TrieMap.empty

  // symbols for extension methods
  val inWorkspaceMethods: TrieMap[Path, Seq[WorkspaceSymbolInformation]] =
    TrieMap.empty[Path, Seq[WorkspaceSymbolInformation]]
  var inDependencies: ClasspathSearch =
    ClasspathSearch.empty

  val topLevelMembers: TrieMap[AbsolutePath, Seq[ToplevelMember]] =
    TrieMap.empty[AbsolutePath, Seq[ToplevelMember]]

  def search(
      query: String,
      fileInFocus: Option[AbsolutePath],
  ): Seq[l.SymbolInformation] = {
    search(query, () => (), fileInFocus)
  }

  def addToplevelMembers(
      toplevelMembers: Map[AbsolutePath, Seq[ToplevelMember]]
  ): Unit = {
    topLevelMembers ++= toplevelMembers
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
      kind: ju.Optional[MemberKind] = ju.Optional.empty(),
  ): (SymbolSearch.Result, Int) = {
    if (kind.isPresent) {
      val typeCount = workspaceToplevelSearch(query, visitor, kind)
      (SymbolSearch.Result.COMPLETE, typeCount)
    } else {
      val workspaceCount = workspaceSearch(query, visitor, target)
      val typeCount = workspaceToplevelSearch(query, visitor, kind)
      val (res, inDepsCount) = inDependencies.search(query, visitor)
      (res, workspaceCount + inDepsCount + typeCount)
    }
  }

  def searchMethods(
      query: String,
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier],
  ): SymbolSearch.Result = {
    workspaceMethodSearch(query, visitor, target)
    SymbolSearch.Result.COMPLETE
  }

  def searchWorkspacePackages(
      visitor: SymbolSearchVisitor,
      target: Option[BuildTargetIdentifier],
  ): SymbolSearch.Result = {
    def loop(packages: TrieMap[String, PackageNode], owner: String): Unit = {
      packages.foreach { case (name, node) =>
        val pkg = s"$owner$name/"
        visitor.visitWorkspacePackage(pkg)
        loop(node.children, pkg)
      }
    }
    target.foreach { id =>
      buildTargets
        .buildTargetTransitiveDependencies(id)
        .foreach { dep =>
          packages.get(dep).foreach(loop(_, ""))
        }
    }
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
    val prev = inWorkspace.remove(path.toNIO)
    buildTargets
      .inverseSources(path)
      .foreach(bti =>
        prev.foreach(wsi => removeWorkspacePackages(wsi.symbols, bti))
      )
  }

  def addWorkspacePackages(
      symbols: Seq[WorkspaceSymbolInformation],
      buildTargetIdentifier: BuildTargetIdentifier,
  ): Unit = {
    val toAdd = symbols.map(_.symbol.split("/").dropRight(1)).distinct
    @tailrec
    def loop(
        current: Seq[String],
        packages: TrieMap[String, PackageNode],
    ): Unit = {
      current match {
        case Nil =>
        case head :: next =>
          val pkg = packages.getOrElseUpdate(head, PackageNode())
          pkg.count += 1
          loop(next, pkg.children)
      }
    }
    toAdd.foreach(pkg =>
      loop(
        pkg.toList,
        packages.getOrElseUpdate(buildTargetIdentifier, TrieMap.empty),
      )
    )
  }

  def removeWorkspacePackages(
      symbols: Seq[WorkspaceSymbolInformation],
      buildTargetIdentifier: BuildTargetIdentifier,
  ): Unit = {
    packages.get(buildTargetIdentifier) match {
      case Some(packages) =>
        val toRemove = symbols.map(_.symbol.split("/").dropRight(1)).distinct
        @tailrec
        def loop(
            current: Seq[String],
            packages: TrieMap[String, PackageNode],
        ): Unit = {
          current match {
            case Nil =>
            case head :: next =>
              packages.get(head).foreach(_.count -= 1)
              packages.synchronized {
                if (packages.get(head).exists(_.count <= 0)) {
                  packages.remove(head)
                }
              }
              packages.get(head) match {
                case Some(pkg) => loop(next, pkg.children)
                case None =>
              }
          }
        }
        toRemove.foreach(pkg => loop(pkg.toList, packages))
      case None =>
    }
  }

  def didChange(
      source: AbsolutePath,
      symbols: Seq[WorkspaceSymbolInformation],
      methodSymbols: Seq[WorkspaceSymbolInformation],
  ): Unit = {
    val bloom = Fuzzy.bloomFilterSymbolStrings(symbols.map(_.symbol))
    val prev = inWorkspace.get(source.toNIO)
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

    buildTargets.inverseSources(source).foreach { buildTargetIdentifier =>
      prev.foreach(prev =>
        removeWorkspacePackages(prev.symbols, buildTargetIdentifier)
      )
      addWorkspacePackages(symbols, buildTargetIdentifier)
    }
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

  private def workspaceToplevelSearch(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      kindFilter: ju.Optional[MemberKind],
  ): Int = {
    val excludedPackages = excludedPackageHandler()
    val all = for {
      (path, symbols) <- topLevelMembers.iterator
      symbol <- symbols
      if query.isClasspath
      if !kindFilter.isPresent || kindFilter.get() == symbol.kind.toJava
      if query.matches(symbol.symbol)
      if !excludedPackages.isExcludedPackage(symbol.symbol)
    } yield {
      visitor.visitWorkspaceSymbol(
        path.toNIO,
        symbol.symbol,
        symbol.kind.toLsp,
        symbol.range.toLsp,
      )
    }
    all.length
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

    val extensionSymbols = for {
      (path, methods) <- id match {
        case None =>
          inWorkspaceMethods.iterator
        case Some(target) =>
          for {
            source <- buildTargets.buildTargetTransitiveSources(target)
            methods <- inWorkspaceMethods.get(source.toNIO)
          } yield (source.toNIO, methods)
      }
      isDeleted = !Files.isRegularFile(path)
      _ = if (isDeleted) inWorkspaceMethods.remove(path)
      if !isDeleted
      symbol <- methods
      if query.matches(symbol.symbol)
    } yield (path, symbol)

    val allSymbols = symbols ++ extensionSymbols

    @tailrec
    def loopSearch(count: Int): Int =
      if (
        !allSymbols.hasNext || (query.isShortQuery && count >= MaxWorkspaceMatchesForShortQuery)
      ) count
      else {
        val (path, symbol) = allSymbols.next()
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
    val query = WorkspaceSymbolQuery.fromTextQuery(textQuery)
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
      extends Ordering[AbsolutePath] {
    private def pathMatchesPreferred(path: AbsolutePath) =
      buildTargets
        .possibleScalaVersions(path)
        .exists(preferredScalaVersions(_))

    private def pathLength(path: AbsolutePath) =
      path.toURI.toString().length()

    override def compare(x: AbsolutePath, y: AbsolutePath): Int = {
      val xVersionMatches = pathMatchesPreferred(x)
      val yVersionMatches = pathMatchesPreferred(y)

      if (xVersionMatches && !yVersionMatches) -1
      else if (yVersionMatches && !xVersionMatches) 1
      else pathLength(x) - pathLength(y)
    }
  }

  object SymbolDefinitionOrdering {
    def fromOptPath(path: Option[AbsolutePath]): Ordering[AbsolutePath] = {
      path.toList.flatMap(buildTargets.possibleScalaVersions(_)) match {
        case Nil => DefaultSymbolDefinitionOrdering
        case preferredScalaVersions =>
          new PreferredScalaVersionOrdering(preferredScalaVersions.toSet)
      }
    }
  }

}
case class PackageNode(
    children: TrieMap[String, PackageNode] = TrieMap.empty
) {
  @volatile var count: Int = 0
}
