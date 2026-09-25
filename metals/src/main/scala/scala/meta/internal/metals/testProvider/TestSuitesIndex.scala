package scala.meta.internal.metals.testProvider

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.TestFrameworkUtils
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath

import bloop.config.Config
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.{bsp4j => b}
import org.eclipse.{lsp4j => l}

final case class FullyQualifiedName(value: String) extends AnyVal
final case class ClassName(value: String) extends AnyVal

private[testProvider] final case class SymbolsPerTarget(
    target: BuildTarget,
    testSymbols: scala.collection.Map[
      BuildTargetClasses.Symbol,
      BuildTargetClasses.TestSymbolInfo,
    ],
)

private[testProvider] final case class TestFileMetadata(
    md5: String,
    entries: List[TestEntry],
    hasTestCasesGranularity: Boolean,
)

private[testProvider] final case class TestEntry(
    buildTarget: BuildTarget,
    path: AbsolutePath,
    suiteDetails: TestSuiteDetails,
)

private[testProvider] final case class TestSuiteDetails(
    fullyQualifiedName: FullyQualifiedName,
    framework: Config.TestFramework,
    className: ClassName,
    symbol: mtags.Symbol,
    location: l.Location,
) {
  def asAddEvent: TestExplorerEvent = AddTestSuite(
    fullyQualifiedClassName = fullyQualifiedName.value,
    className = className.value,
    symbol = symbol.value,
    location = location,
    canResolveChildren = TestFrameworkUtils.canResolveTests(framework),
  )

  def asRemoveEvent: TestExplorerEvent = RemoveTestSuite(
    fullyQualifiedClassName = fullyQualifiedName.value,
    className = className.value,
  )
}

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
   *
   * Note: Uses BuildTargetIdentifier as key instead of BuildTarget because
   * BuildTarget from BSP4J doesn't have proper equals/hashCode for use as map keys.
   */
  private val cachedTestSuites =
    TrieMap[
      b.BuildTargetIdentifier,
      TrieMap[FullyQualifiedName, TestEntry],
    ]()
  private val fileToMetadata = TrieMap[AbsolutePath, TestFileMetadata]()

  /**
   * Last known test case names per suite,
   * as reported to the client via `AddTestCases`.
   */
  private val cachedTestCaseNames =
    TrieMap[(b.BuildTargetIdentifier, FullyQualifiedName), List[String]]()

  def putTestCases(
      buildTarget: BuildTarget,
      fullyQualifiedName: FullyQualifiedName,
      testCases: List[TestCaseEntry],
  ): Unit =
    cachedTestCaseNames.put(
      (buildTarget.getId, fullyQualifiedName),
      testCases.map(_.name),
    )

  def getTestCaseNames(
      buildTarget: BuildTarget,
      fullyQualifiedName: FullyQualifiedName,
  ): List[String] =
    cachedTestCaseNames.getOrElse((buildTarget.getId, fullyQualifiedName), Nil)

  def allSuites: Vector[(BuildTarget, Iterable[TestEntry])] =
    cachedTestSuites.toVector.flatMap { case (_, suites) =>
      suites.values.headOption.map(entry => (entry.buildTarget, suites.values))
    }

  def put(
      entry: TestEntry
  ): Unit = {
    val fullyQualifiedName = entry.suiteDetails.fullyQualifiedName
    val targetId = entry.buildTarget.getId
    cachedTestSuites.get(targetId) match {
      case Some(suites) =>
        suites.put(fullyQualifiedName, entry)
      case None =>
        val suites = TrieMap(fullyQualifiedName -> entry)
        cachedTestSuites.put(targetId, suites)
    }

    fileToMetadata.get(entry.path) match {
      case Some(metadata) =>
        val updated = metadata.copy(entries = entry :: metadata.entries)
        fileToMetadata.put(entry.path, updated)
      case None =>
        val metadata = TestFileMetadata("", List(entry), false)
        fileToMetadata.put(entry.path, metadata)
    }
  }

  def updateFileMetadata(path: AbsolutePath, md5: String): Unit =
    fileToMetadata.get(path).foreach { metadata =>
      val updated = metadata.copy(hasTestCasesGranularity = true, md5 = md5)
      fileToMetadata.update(path, updated)
    }

  def contains(path: AbsolutePath): Boolean = fileToMetadata.contains(path)

  /**
   * Determine if test cases should be updated for a given file after compilation
   * @param path - file path
   * @param md5 - md5 of updated file
   */
  def shouldBeUpdated(path: AbsolutePath, md5: String): Boolean =
    fileToMetadata
      .get(path)
      .exists { metadata =>
        metadata.hasTestCasesGranularity && md5 != metadata.md5
      }

  def getMetadata(path: AbsolutePath): Option[TestFileMetadata] =
    fileToMetadata.get(path)

  def getSuiteNames(
      buildTarget: BuildTarget
  ): Set[FullyQualifiedName] =
    cachedTestSuites
      .get(buildTarget.getId)
      .map(_.keySet.toSet)
      .getOrElse(Set.empty)

  def get(target: BuildTarget, name: FullyQualifiedName): Option[TestEntry] =
    cachedTestSuites.get(target.getId).flatMap(_.get(name))

  def remove(
      buildTarget: BuildTarget,
      suiteName: FullyQualifiedName,
  ): Option[TestEntry] = {
    val targetId = buildTarget.getId
    for {
      suites <- cachedTestSuites.get(targetId)
      entry <- suites.remove(suiteName)
    } yield {
      cachedTestCaseNames.remove((targetId, suiteName))
      fileToMetadata.get(entry.path).foreach { metadata =>
        val filtered =
          metadata.entries
            .filter(_.suiteDetails.fullyQualifiedName.value != suiteName.value)
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
      remove(entry.buildTarget, entry.suiteDetails.fullyQualifiedName)
      entry
    }
  }

}
