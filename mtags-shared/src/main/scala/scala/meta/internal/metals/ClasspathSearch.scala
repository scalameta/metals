package scala.meta.internal.metals

import java.nio.file.Path
import java.util.PriorityQueue

import scala.collection.JavaConverters._

import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionJavaPriorityQueue
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor
class ClasspathSearch(
    val packages: Array[CompressedPackageIndex]
) {
  // The maximum number of non-exact matches that we return for classpath queries.
  // Generic queries like "Str" can returns several thousand results, so we need
  // to limit it at some arbitrary point. Exact matches are always included.
  private val maxNonExactMatches = 10

  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor
  ): (SymbolSearch.Result, Int) = {
    if (query.query == "_") return (SymbolSearch.Result.COMPLETE, 0)
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
    var exactMatches = 0
    var searchResult =
      if (query.isShortQuery) SymbolSearch.Result.INCOMPLETE
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
      if (added > 0) {
        if (!hit.isExact(query)) nonExactMatches += added
        else exactMatches += added
      }
    }
    (searchResult, nonExactMatches + exactMatches)
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
      if query.matches(classfile.fullname)
    } yield classfile
  }
}

object ClasspathSearch {
  def empty: ClasspathSearch =
    new ClasspathSearch(Array.empty)

  def fromClasspath(
      classpath: collection.Seq[Path],
      excludePackages: ExcludedPackagesHandler,
      bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
  ): ClasspathSearch =
    Indexer.default.index(classpath, excludePackages, bucketSize)

  trait Indexer {
    def index(
        classpath: collection.Seq[Path],
        excludePackages: ExcludedPackagesHandler,
        bucketSize: Int = CompressedPackageIndex.DefaultBucketSize
    ): ClasspathSearch
  }

  object Indexer {

    val default: Indexer =
      (classpath, excludePackages, bucketSize) => {
        val packages = PackageIndex.fromClasspath(
          classpath,
          excludePackages.isExcludedPackage
        )
        val map = CompressedPackageIndex.fromPackages(
          () =>
            packages.packages.asScala.iterator.map { pkg =>
              (pkg._1, pkg._2.asScala)
            },
          excludePackages.isExcludedPackage,
          bucketSize
        )
        new ClasspathSearch(map)
      }
  }
}
