package scala.meta.internal.metals

import java.nio.file.Path
import java.util.PriorityQueue
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.internal.mtags.MtagsEnrichments._

class ClasspathSearch(
    val packages: Array[CompressedPackageIndex],
    packagePriority: String => Int
) {
  // The maximum number of non-exact matches that we return for classpath queries.
  // Generic queries like "Str" can returns several thousand results, so we need
  // to limit it at some arbitrary point. Exact matches are always included.
  private val maxNonExactMatches = 10

  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    if (query.query == "_") return SymbolSearch.Result.COMPLETE
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
    var searchResult =
      if (query.isExact) SymbolSearch.Result.INCOMPLETE
      else SymbolSearch.Result.COMPLETE
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

  private def search(
      query: WorkspaceSymbolQuery,
      shouldVisitPackage: String => Boolean,
      isCancelled: () => Boolean
  ): Iterator[Classfile] = {
    for {
      pkg <- packages.iterator
      if pkg.packages.exists(shouldVisitPackage)
      if !isCancelled()
      if query.matches(pkg.bloom)
      classfile <- pkg.members
      if classfile.isClassfile
      isMatch = {
        if (query.isExact) Fuzzy.isExactMatch(query.query, classfile.filename)
        else query.matches(classfile.fullname)
      }
      if isMatch
    } yield classfile
  }
}

object ClasspathSearch {
  def empty: ClasspathSearch =
    new ClasspathSearch(Array.empty, _ => 0)
  def fromPackages(
      packages: PackageIndex,
      packagePriority: String => Int,
      bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
  ): ClasspathSearch = {
    val map = CompressedPackageIndex.fromPackages(packages, bucketSize)
    new ClasspathSearch(map, packagePriority)
  }
  def fromClasspath(
      classpath: collection.Seq[Path],
      packagePriority: String => Int,
      bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
  ): ClasspathSearch = {
    val packages = PackageIndex.fromClasspath(classpath)
    fromPackages(packages, packagePriority, bucketSize)
  }
}
