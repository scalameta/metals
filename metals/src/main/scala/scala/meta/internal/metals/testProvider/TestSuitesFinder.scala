package scala.meta.internal.metals.testProvider

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.BuildTargetClasses

import ch.epfl.scala.bsp4j.BuildTarget

final class TestSuitesFinder(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    definitionProvider: DefinitionProvider
) {

  /**
   * Find test suites for all build targets in current projects.
   */
  def findTestSuites(): Seq[TestSuiteDiscoveryResult] = {
    buildTargets.allBuildTargetIds.toList
      .flatMap(buildTargets.info)
      .filterNot(_.isSbtBuild)
      .map(buildTarget => createTestDiscoveryFor(buildTarget))
      // don't send empty results
      .filter(_.discovered.asScala.nonEmpty)
  }

  private def createTestDiscoveryFor(buildTarget: BuildTarget) = {
    val classes = buildTargetClasses
      .classesOf(buildTarget.getId)
      .testClasses
      .toList
      .flatMap { case (symbol, fullyQualifiedClassName) =>
        createTestEntry(buildTarget, symbol, fullyQualifiedClassName)
      }
    val grouped = groupTestsByPackage(classes)

    TestSuiteDiscoveryResult(
      buildTarget.getDisplayName,
      buildTarget.getId.getUri,
      grouped.asJava
    )
  }

  private def createTestEntry(
      buildTarget: BuildTarget,
      symbol: String,
      fullyQualifiedClassName: String
  ): Option[TestEntry] = {
    definitionProvider.toLocation(symbol, List(buildTarget.getId)).map {
      location =>
        // fullyQualifiedClassName always contains at least one element - class name
        val fullyQualifiedName = fullyQualifiedClassName.split('.').toList
        val testClass = TestSuiteDiscoveryResult.TestSuite(
          fullyQualifiedClassName,
          fullyQualifiedName.takeRight(1).head,
          location
        )
        TestEntry(
          fullyQualifiedName.dropRight(1),
          testClass
        )
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
    testClass: TestSuiteDiscoveryResult.TestSuite
) {
  def stripPackage(): TestEntry =
    this.copy(packageParts = this.packageParts.drop(1))
}
