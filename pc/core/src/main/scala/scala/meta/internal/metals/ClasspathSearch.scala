package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.Comparator
import java.util.PriorityQueue
import scala.collection.concurrent.TrieMap
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.internal.mtags.MtagsEnrichments._

class ClasspathSearch(
    val map: collection.Map[String, CompressedPackageIndex],
    packagePriority: String => Int
) {
  // The maximum number of non-exact matches that we return for classpath queries.
  // Generic queries like "Str" can returns several thousand results, so we need
  // to limit it at some arbitrary point. Exact matches are always included.
  private val maxNonExactMatches = 10
  private val byReferenceThenAlphabeticalComparator = new Comparator[String] {
    override def compare(a: String, b: String): Int = {
      val byReference = -Integer.compare(packagePriority(a), packagePriority(b))
      if (byReference != 0) byReference
      else a.compare(b)
    }
  }

  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    val classfiles =
      new PriorityQueue[Classfile](new ClassfileComparator(query.query))
    for {
      classfile <- search(
        query,
        pkg => visitor.shouldVisitPackage(pkg),
        () => visitor.isCancelled
      )
    } {
      classfiles.add(classfile)
    }
    var nonExactMatches = 0
    var searchResult = SymbolSearch.Result.COMPLETE
    for {
      hit <- classfiles.pollingIterator
      if {
        val isContinue = !visitor.isCancelled &&
          (nonExactMatches < maxNonExactMatches || hit.isExact(query))
        if (!isContinue) {
          searchResult = SymbolSearch.Result.INCOMPLETE
        }
        isContinue
      }
    } {
      val added = visitor.visitClassfile(hit.pkg, hit.filename)
      if (added > 0 && !hit.isExact(query)) {
        nonExactMatches += added
      }
    }
    searchResult
  }

  private def packagesSortedByReferences(): Array[String] = {
    val packages = map.keys.toArray
    util.Arrays.sort(packages, byReferenceThenAlphabeticalComparator)
    packages
  }

  private def search(
      query: WorkspaceSymbolQuery,
      visitPackage: String => Boolean,
      isCancelled: () => Boolean
  ): Iterator[Classfile] = {
    val packages = packagesSortedByReferences()
    for {
      pkg <- packages.iterator
      if visitPackage(pkg)
      if !isCancelled()
      compressed = map(pkg)
      if query.matches(compressed.bloom)
      member <- compressed.members
      if member.endsWith(".class")
      symbol = new ConcatSequence(pkg, member)
      isMatch = query.matches(symbol)
      if isMatch
    } yield {
      Classfile(pkg, member)
    }
  }

}

object ClasspathSearch {
  def empty: ClasspathSearch =
    new ClasspathSearch(Map.empty, _ => 0)
  def fromPackages(
      packages: PackageIndex,
      packagePriority: String => Int
  ): ClasspathSearch = {
    val map = TrieMap.empty[String, CompressedPackageIndex]
    map ++= CompressedPackageIndex.fromPackages(packages)
    new ClasspathSearch(map, packagePriority)
  }
  def fromClasspath(
      classpath: Seq[Path],
      packagePriority: String => Int
  ): ClasspathSearch = {
    val packages = new PackageIndex()
    packages.visitBootClasspath()
    classpath.foreach { path =>
      packages.visit(AbsolutePath(path))
    }
    fromPackages(packages, packagePriority)
  }
}
