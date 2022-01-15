package scala.meta.internal.metals.testProvider

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.BuildTargetClasses.FullyQualifiedClassName
import scala.meta.internal.metals.debug.BuildTargetClasses.Symbol

import ch.epfl.scala.bsp4j.BuildTarget

private final case class SymbolsPerTarget(
    target: BuildTarget,
    testSymbols: TrieMap[Symbol, FullyQualifiedClassName]
)

final class TestSuitesProvider(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    definitionProvider: DefinitionProvider,
    clientConfig: ClientConfiguration
)(implicit ec: ExecutionContext) {

  /**
   * Cached, already discovered test suites.
   * Cache is updated via refreshTestSuites method called after compilation is finished.
   * There is no need to have synchronization since all access is available via
   * single threaded executor.
   *
   * For every test suite a.MunitTestSuite BSP returns 2 symbols. However, only one of them
   * has location in source code and it's useful.
   * That's why FullyQualifiedClassName is used as a key.
   * "a/MunitTestSuite., a.MunitTestSuite"
   * "a/MunitTestSuite#, a.MunitTestSuite"
   */
  private val cachedTestSuites =
    mutable.Map[FullyQualifiedClassName, TestEntry]()

  val refreshTestSuites = new BatchedFunction[Unit, Unit](_ =>
    CancelableFuture(doRefreshTestSuites())
  )

  /**
   * Retrieves cached test suites.
   */
  def findTestSuites(): java.util.List[TestSuiteDiscoveryResult] = {
    cachedTestSuites.values
      .groupBy(_.buildTarget)
      .toSeq
      .map { case (buildTarget, entries) =>
        val grouped = groupTestsByPackage(entries.toList)
        TestSuiteDiscoveryResult(
          buildTarget.getDisplayName(),
          buildTarget.getId().getUri(),
          grouped.asJava
        )
      }
      .asJava
  }

  /**
   * Find test suites for all build targets in current projects and update caches.
   */
  private def doRefreshTestSuites(): Future[Unit] =
    if (clientConfig.isTestExplorerProvider()) Future {
      val buildTargetList = buildTargets.allBuildTargetIds.toList
        .flatMap(buildTargets.info)
        .filterNot(_.isSbtBuild)

      val symbolsPerTarget = buildTargetList
        .map(buildTarget =>
          SymbolsPerTarget(
            buildTarget,
            buildTargetClasses.classesOf(buildTarget.getId).testClasses
          )
        )

      // when test suite is deleted it has to be removed from cache
      val classNamesFromBsp =
        symbolsPerTarget.flatMap(_.testSymbols.values).toSet
      val classNamesToRemove = cachedTestSuites.keySet -- classNamesFromBsp
      classNamesToRemove.foreach { removedClass =>
        cachedTestSuites.remove(removedClass)
      }

      for {
        SymbolsPerTarget(buildTarget, testSymbols) <- symbolsPerTarget
        (symbol, fullyQualifiedClassName) <- testSymbols
          .readOnlySnapshot()
          .toList
        // IMPORTANT this check is meant to check for class name, not a symbol
        if !cachedTestSuites.contains(fullyQualifiedClassName)
      } computeTestEntry(buildTarget, symbol, fullyQualifiedClassName)
    }
    else Future.successful(())

  private def computeTestEntry(
      buildTarget: BuildTarget,
      symbol: String,
      fullyQualifiedClassName: String
  ): Unit = {
    definitionProvider.toLocation(symbol, List(buildTarget.getId)).foreach {
      location =>
        // fullyQualifiedClassName always contains at least one element - class name
        val fullyQualifiedName = fullyQualifiedClassName.split('.').toList

        val testClass = TestSuiteDiscoveryResult.TestSuite(
          fullyQualifiedClassName,
          fullyQualifiedName.takeRight(1).head,
          location
        )

        val entry = TestEntry(
          fullyQualifiedName.dropRight(1),
          buildTarget,
          testClass
        )

        cachedTestSuites.put(fullyQualifiedClassName, entry)
        entry
    }
  }

  /**
   * Partitions the given testEntries depending on whether entry has nonempty package or not.
   * Mapped those empty ones into TestClass, strip package for nonempty ones and repeat process for them.
   */
  private def groupTestsByPackage(
      testEntries: List[TestEntry]
  ): List[TestSuiteDiscoveryResult.Discovered] = {
    val (withPackage, withoutPackage) =
      testEntries.partition(entry => entry.packageParts.nonEmpty)
    val currentTestClasses = withoutPackage.map(_.testClass)
    val testClassesInPackages: Iterable[TestSuiteDiscoveryResult.Discovered] =
      withPackage
        .groupBy(entry => entry.packageParts.head)
        .mapValues(
          _.map(entry => entry.copy(packageParts = entry.packageParts.drop(1)))
        )
        .map { case (prefix, entries) =>
          val children = groupTestsByPackage(entries)
          TestSuiteDiscoveryResult.Package(prefix, children.asJava)
        }
    val result = currentTestClasses ++ testClassesInPackages
    result
  }
}

private case class TestEntry(
    packageParts: List[String],
    buildTarget: BuildTarget,
    testClass: TestSuiteDiscoveryResult.TestSuite
) {
  def stripPackage(): TestEntry =
    this.copy(packageParts = this.packageParts.drop(1))
}
