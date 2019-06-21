package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.Comparator
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
  private val byReferenceThenAlphabeticalComparator =
    new Comparator[CompressedPackageIndex] {
      override def compare(
          a: CompressedPackageIndex,
          b: CompressedPackageIndex
      ): Int = {
        val byReference = -Integer.compare(
          packagePriority(a.packages(0)),
          packagePriority(b.packages(0))
        )
        if (byReference != 0) byReference
        else a.compare(b)
      }
    }

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

  private def packagesSortedByReferences(): Array[CompressedPackageIndex] = {
    util.Arrays.sort(packages, byReferenceThenAlphabeticalComparator)
    packages
  }

  private def search(
      query: WorkspaceSymbolQuery,
      shouldVisitPackage: String => Boolean,
      isCancelled: () => Boolean
  ): Iterator[Classfile] = {
    val packages = packagesSortedByReferences()
    for {
      pkg <- packages.iterator
      if pkg.packages.exists(shouldVisitPackage)
      if !isCancelled()
      if query.matches(pkg.bloom)
      classfile <- pkg.members
      if classfile.isClassfile
      isMatch = query.matches(classfile.fullname)
      if isMatch
    } yield classfile
  }
}

object ClasspathSearch {
  def empty: ClasspathSearch =
    new ClasspathSearch(Array.empty, _ => 0)
  def fromPackages(
      packages: PackageIndex,
      packagePriority: String => Int
  ): ClasspathSearch = {
    val map = CompressedPackageIndex.fromPackages(packages)
    new ClasspathSearch(map, packagePriority)
  }
  def fromClasspath(
      classpath: collection.Seq[Path],
      packagePriority: String => Int
  ): ClasspathSearch = {
    val packages = PackageIndex.fromClasspath(classpath)
    fromPackages(packages, packagePriority)
  }
}
