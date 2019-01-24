package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util
import java.util.Comparator
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

/**
 * Implements workspace/symbol for both workspace sources and dependency classpath.
 */
final class WorkspaceSymbolProvider(
    val workspace: AbsolutePath,
    statistics: StatisticsConfig,
    val buildTargets: BuildTargets,
    val index: OnDemandSymbolIndex,
    isReferencedPackage: String => Boolean,
    fileOnDisk: AbsolutePath => AbsolutePath
)(implicit ec: ExecutionContext) {
  val inWorkspace = TrieMap.empty[Path, BloomFilter[CharSequence]]
  val inDependencies = TrieMap.empty[String, CompressedPackageIndex]
  // The maximum number of non-exact matches that we return for classpath queries.
  // Generic queries like "Str" can returns several thousand results, so we need
  // to limit it at some arbitrary point. Exact matches are always included.
  private val maxNonExactMatches = 10

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
      symbols: Seq[String]
  ): Unit = {
    val bloomFilterStrings = Fuzzy.bloomFilterSymbolStrings(symbols)
    val bloom = BloomFilter.create[CharSequence](
      Funnels.stringFunnel(StandardCharsets.UTF_8),
      Integer.valueOf(bloomFilterStrings.size),
      0.01
    )
    inWorkspace(source.toNIO) = bloom
    bloomFilterStrings.foreach { c =>
      bloom.put(c)
    }
  }

  private def isExcludedPackage(pkg: String): Boolean = {
    // NOTE(olafur) I can't count how many times I've gotten unwanted results from these packages.
    pkg.startsWith("com/sun/") ||
    pkg.startsWith("com/apple/")
  }
  private def indexClasspathUnsafe(): Unit = {
    inDependencies.clear()
    val packages = new PackageIndex()
    packages.expandJdkClasspath()
    for {
      target <- buildTargets.all
      classpathEntry <- target.scalac.classpath
      if classpathEntry.extension == "jar"
    } {
      packages.visit(classpathEntry)
    }
    for {
      (pkg, members) <- packages.packages.asScala
      if !isExcludedPackage(pkg)
    } {
      val buf = Fuzzy.bloomFilterSymbolStrings(members.asScala)
      buf ++= Fuzzy.bloomFilterSymbolStrings(List(pkg), buf)
      val bloom = BloomFilters.create(buf.size)
      buf.foreach { key =>
        bloom.put(key)
      }
      // Sort members for deterministic order for deterministic results.
      members.sort(String.CASE_INSENSITIVE_ORDER)
      // Compress members because they make up the bulk of memory usage in the classpath index.
      // For a 140mb classpath with spark/linkerd/akka/.. the members take up 12mb uncompressed
      // and ~900kb compressed. We are accummulating a lot of different custom indexes in Metals
      // so we should try to keep each of them as small as possible.
      val compressedMembers = Compression.compress(members.asScala)
      inDependencies(pkg) = CompressedPackageIndex(bloom, compressedMembers)
    }
  }

  private val byReferenceThenAlphabeticalComparator = new Comparator[String] {
    override def compare(a: String, b: String): Int = {
      val isReferencedA = isReferencedPackage(a)
      val isReferencedB = isReferencedPackage(b)
      val byReference =
        -java.lang.Boolean.compare(isReferencedA, isReferencedB)
      if (byReference != 0) byReference
      else a.compare(b)
    }
  }

  private def packagesSortedByReferences(): Array[String] = {
    val packages = inDependencies.keys.toArray
    util.Arrays.sort(packages, byReferenceThenAlphabeticalComparator)
    packages
  }

  private def searchUnsafe(
      textQuery: String,
      token: CancelChecker
  ): Seq[l.SymbolInformation] = {
    val result = new PriorityQueue[l.SymbolInformation](
      (o1, o2) => -Integer.compare(o1.getName.length, o2.getName.length)
    )
    val query = WorkspaceSymbolQuery.fromTextQuery(textQuery)
    def matches(info: s.SymbolInformation): Boolean = {
      WorkspaceSymbolProvider.isRelevantKind(info.kind) &&
      query.matches(info.symbol)
    }
    def searchWorkspaceSymbols(): Unit = {
      val timer = new Timer(Time.system)
      var visitsCount = 0
      var falsePositives = 0
      for {
        (path, bloom) <- inWorkspace
        _ = token.checkCanceled()
        if query.matches(bloom)
      } {
        visitsCount += 1
        var isFalsePositive = true
        val input = path.toUriInput
        SemanticdbDefinition.foreach(input) { defn =>
          if (matches(defn.info)) {
            isFalsePositive = false
            result.add(defn.toLSP(input.path))
          }
        }
        if (isFalsePositive) {
          falsePositives += 1
        }
      }
    }
    def searchDependencySymbols(): Unit = {
      val timer = new Timer(Time.system)
      val classfiles = new PriorityQueue[Classfile](
        (a, b) => Integer.compare(a.filename.length, b.filename.length)
      )
      val packages = packagesSortedByReferences()
      for {
        pkg <- packages.iterator
        compressed = inDependencies(pkg)
        _ = token.checkCanceled()
        if query.matches(compressed.bloom)
        member <- compressed.members
        if member.endsWith(".class")
        name = member.subSequence(0, member.length - ".class".length)
        symbol = new ConcatSequence(pkg, name)
        isMatch = query.matches(symbol)
        if isMatch
      } {
        classfiles.add(Classfile(pkg, member))
      }
      val classpathEntries = ArrayBuffer.empty[l.SymbolInformation]
      val isVisited = mutable.Set.empty[AbsolutePath]
      var nonExactMatches = 0
      for {
        hit <- classfiles.pollingIterator
        _ = token.checkCanceled()
        if nonExactMatches < maxNonExactMatches || hit.isExact(query)
        defn <- hit.definition(index)
        if !isVisited(defn.path)
      } {
        isVisited += defn.path
        if (!hit.isExact(query)) {
          nonExactMatches += 1
        }
        val input = defn.path.toInput
        lazy val uri = fileOnDisk(defn.path).toURI.toString
        SemanticdbDefinition.foreach(input) { defn =>
          if (matches(defn.info)) {
            classpathEntries += defn.toLSP(uri)
          }
        }
      }
      classpathEntries.foreach { s =>
        result.add(s)
      }
    }
    searchWorkspaceSymbols()
    searchDependencySymbols()
    result.asScala.toSeq.sortBy(_.getName.length)
  }
}

object WorkspaceSymbolProvider {
  def isRelevantKind(kind: Kind): Boolean = {
    kind match {
      case Kind.OBJECT | Kind.PACKAGE_OBJECT | Kind.CLASS | Kind.TRAIT |
          Kind.INTERFACE =>
        true
      case _ =>
        false
    }
  }
}
