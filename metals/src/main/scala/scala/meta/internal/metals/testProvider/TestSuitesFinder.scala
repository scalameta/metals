package scala.meta.internal.metals.testProvider

import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.BuildTargetClasses

import ch.epfl.scala.bsp4j.BuildTarget

trait TestSuitesFinder {
  def findTestSuites(): Seq[TestDiscovery]
}

final class TestSuitesFinderImpl(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    definitionProvider: DefinitionProvider,
    implementationProvider: ImplementationProvider
) extends TestSuitesFinder {

  override def findTestSuites(): Seq[TestDiscovery] = {
    buildTargets.allBuildTargetIds.toList
      .flatMap(buildTargets.info)
      .filterNot(_.getDisplayName.endsWith("build"))
      .map(buildTarget => createTestDiscoveryFor(buildTarget))
      // don't send empty results
      .filter(_.discovered.asScala.exists(_.nonEmpty))
  }

  private def createTestDiscoveryFor(buildTarget: BuildTarget) = {
    val classes = buildTargetClasses
      .classesOf(buildTarget.getId)
      .testClasses
      // filter out symbols which don't represent classes
      .filter { case (symbol, _) => symbol.endsWith("#") }
      .toList
      .map { case (symbol, className) =>
        createTestEntry(buildTarget, symbol, className)
      }

    TestDiscovery(
      buildTarget.getDisplayName,
      buildTarget.getId.getUri,
      TestProviderImpl.groupTestsByPackage(classes).asJava
    )
  }

  private def createTestEntry(
      buildTarget: BuildTarget,
      symbol: String,
      className: String
  ): TestEntry = {
    val location = for {
      definition <- definitionProvider
        .toPath(symbol, List(buildTarget.getId))
      location <- definition.toResult.flatMap(
        _.locations.asScala.toList
          .filter(_.getUri == definition.uri)
          .headOption
      )
    } yield location
    val fullyQualifiedName = className.split('.').toList
    val testClass = TestDiscovery.TestSuite(
      className,
      fullyQualifiedName.takeRight(1).head,
      location.orNull
    )
    TestEntry(
      fullyQualifiedName.dropRight(1),
      testClass
    )
  }
}

private case class TestEntry(
    packageParts: List[String],
    testClass: TestDiscovery.TestSuite
) {
  def stripPackage(): TestEntry =
    this.copy(packageParts = this.packageParts.drop(1))
}

object TestProviderImpl {
  def groupTestsByPackage(
      testEntries: List[TestEntry]
  ): List[TestDiscovery.Result] = groupTestsByPackageImpl(testEntries)

  /**
   * Partitions the given testEntries depending on whether entry has nonempty package or not.
   * Mapped those empty ones into TestClass, strip package for nonempty ones and repeat process for them.
   */
  private def groupTestsByPackageImpl(
      testEntries: List[TestEntry]
  ): List[TestDiscovery.Result] = {
    val (withPackage, withoutPackage) =
      testEntries.partition(e => e.packageParts.nonEmpty)
    val currentTestClasses = withoutPackage.map(_.testClass)
    val testClassesInPackages: Iterable[TestDiscovery.Result] = withPackage
      .groupBy(p => p.packageParts.head)
      .mapValues(_.map(p => p.copy(packageParts = p.packageParts.drop(1))))
      .map { case (prefix, entries) =>
        val children = groupTestsByPackageImpl(entries)
        TestDiscovery.Package(prefix, children.asJava)
      }
    val result = currentTestClasses ++ testClassesInPackages
    result
  }
}
