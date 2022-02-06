package scala.meta.internal.metals.testProvider

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.JavacOptionsItem
import ch.epfl.scala.bsp4j.ScalacOptionsItem

final case class FullyQualifiedName(value: String) extends AnyVal
final case class ClassName(value: String) extends AnyVal

private[testProvider] final case class SymbolsPerTarget private (
    target: BuildTarget,
    testSymbols: TrieMap[
      BuildTargetClasses.Symbol,
      BuildTargetClasses.FullyQualifiedClassName
    ],
    private val classpath: List[String]
) {
  def hasJunitOnClasspath: Boolean =
    classpath.exists(_.contains("junit-interface"))
}
object SymbolsPerTarget {
  def apply(
      target: BuildTarget,
      testSymbols: TrieMap[
        BuildTargetClasses.Symbol,
        BuildTargetClasses.FullyQualifiedClassName
      ],
      scalac: Option[ScalacOptionsItem],
      javac: Option[JavacOptionsItem]
  ): SymbolsPerTarget = {
    SymbolsPerTarget(
      target,
      testSymbols,
      scalac
        .map(_.getClasspath())
        .orElse(javac.map(_.getClasspath()))
        .map(_.asScala.toList)
        .getOrElse(Nil)
    )
  }
}

private[testProvider] final case class TestFileMetadata(
    entries: List[TestEntry],
    hasTestCasesGranularity: Boolean
)

private[testProvider] final case class TestEntry(
    buildTarget: BuildTarget,
    path: AbsolutePath,
    suiteInfo: TestSuiteInfo,
    testClass: AddTestSuite
)

private[testProvider] final case class TestSuiteInfo(
    fullyQualifiedName: FullyQualifiedName,
    className: ClassName,
    symbol: mtags.Symbol
)

private[testProvider] final class TestSuitesIndex {

  /**
   * Cached, already discovered test suites per build target.
   *
   * For every test suite a.TestSuiteName Metals returns 2 symbols,
   * one for object and one for class.
   * However, only one of them has location in source code and it's useful.
   * That's why FullyQualifiedClassName is used as a key.
   * "a/TestSuiteName., a.TestSuiteName"
   * "a/TestSuiteName#, a.TestSuiteName"
   */
  private val cachedTestSuites =
    TrieMap[
      BuildTarget,
      TrieMap[FullyQualifiedName, TestEntry]
    ]()
  private val fileToMetadata = TrieMap[AbsolutePath, TestFileMetadata]()

  def allSuites: Iterable[(BuildTarget, Iterable[TestEntry])] =
    cachedTestSuites.mapValues(_.values).toIterable

  def put(
      entry: TestEntry
  ): Unit = {
    val fullyQualifiedName = entry.suiteInfo.fullyQualifiedName
    cachedTestSuites.get(entry.buildTarget) match {
      case Some(suites) =>
        suites.put(fullyQualifiedName, entry)
      case None =>
        val suites = TrieMap(fullyQualifiedName -> entry)
        cachedTestSuites.put(entry.buildTarget, suites)
    }

    fileToMetadata.get(entry.path) match {
      case Some(metadata) =>
        val updated = metadata.copy(entries = entry :: metadata.entries)
        fileToMetadata.put(entry.path, updated)
      case None =>
        val metadata = TestFileMetadata(List(entry), false)
        fileToMetadata.put(entry.path, metadata)
    }
  }

  def setHasTestCasesGranularity(path: AbsolutePath): Unit =
    fileToMetadata.get(path).foreach { metadata =>
      val updated = metadata.copy(hasTestCasesGranularity = true)
      fileToMetadata.update(path, updated)
    }

  def contains(path: AbsolutePath): Boolean = fileToMetadata.contains(path)

  def hasTestCasesGranularity(path: AbsolutePath): Boolean =
    fileToMetadata.get(path).map(_.hasTestCasesGranularity).getOrElse(false)

  def getMetadata(path: AbsolutePath): Option[TestFileMetadata] =
    fileToMetadata.get(path)

  def getTestEntry(
      buildTarget: BuildTarget,
      name: FullyQualifiedName
  ): Option[TestEntry] =
    for {
      suites <- cachedTestSuites.get(buildTarget)
      entry <- suites.get(name)
    } yield entry

  def getSuites(
      buildTarget: BuildTarget
  ): mutable.Map[FullyQualifiedName, TestEntry] =
    cachedTestSuites.get(buildTarget).getOrElse(mutable.Map.empty)

  def getSuiteNames(
      buildTarget: BuildTarget
  ): Set[FullyQualifiedName] =
    cachedTestSuites.get(buildTarget).map(_.keySet.toSet).getOrElse(Set.empty)

  def remove(
      buildTarget: BuildTarget,
      suiteName: FullyQualifiedName
  ): Option[TestEntry] = {
    for {
      suites <- cachedTestSuites.get(buildTarget)
      entry <- suites.remove(suiteName)
    } yield {
      fileToMetadata.get(entry.path).foreach { metadata =>
        val filtered =
          metadata.entries
            .filter(_.testClass.fullyQualifiedClassName != suiteName.value)
        if (filtered.isEmpty)
          fileToMetadata.remove(entry.path)
        else
          fileToMetadata.put(entry.path, metadata.copy(entries = filtered))
      }
      entry
    }
  }

  def remove(path: AbsolutePath): List[TestEntry] = {
    for {
      metadata <- fileToMetadata.remove(path).toList
      entry <- metadata.entries
    } yield {
      remove(entry.buildTarget, entry.suiteInfo.fullyQualifiedName)
      entry
    }
  }

}
