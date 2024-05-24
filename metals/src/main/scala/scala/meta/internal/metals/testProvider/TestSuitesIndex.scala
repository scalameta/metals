package scala.meta.internal.metals.testProvider

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.TestFramework
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import org.eclipse.{lsp4j => l}

final case class FullyQualifiedName(value: String) extends AnyVal
final case class ClassName(value: String) extends AnyVal

private[testProvider] final case class SymbolsPerTarget(
    target: BuildTarget,
    testSymbols: TrieMap[
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
    framework: TestFramework,
    className: ClassName,
    symbol: mtags.Symbol,
    location: l.Location,
) {
  def asAddEvent: TestExplorerEvent = AddTestSuite(
    fullyQualifiedClassName = fullyQualifiedName.value,
    className = className.value,
    symbol = symbol.value,
    location = location,
    canResolveChildren = framework.canResolveChildren,
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
   */
  private val cachedTestSuites =
    TrieMap[
      BuildTarget,
      TrieMap[FullyQualifiedName, TestEntry],
    ]()
  private val fileToMetadata = TrieMap[AbsolutePath, TestFileMetadata]()

  def allSuites: Vector[(BuildTarget, Iterable[TestEntry])] =
    cachedTestSuites.mapValues(_.values).toVector

  def put(
      entry: TestEntry
  ): Unit = {
    val fullyQualifiedName = entry.suiteDetails.fullyQualifiedName
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
    cachedTestSuites.get(buildTarget).map(_.keySet.toSet).getOrElse(Set.empty)

  def get(target: BuildTarget, name: FullyQualifiedName): Option[TestEntry] =
    cachedTestSuites.get(target).flatMap(_.get(name))

  def remove(
      buildTarget: BuildTarget,
      suiteName: FullyQualifiedName,
  ): Option[TestEntry] = {
    for {
      suites <- cachedTestSuites.get(buildTarget)
      entry <- suites.remove(suiteName)
    } yield {
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
