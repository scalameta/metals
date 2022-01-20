package scala.meta.internal.metals.testProvider

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.mtags
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget

private[testProvider] final case class SymbolsPerTarget(
    target: BuildTarget,
    testSymbols: TrieMap[
      BuildTargetClasses.Symbol,
      BuildTargetClasses.FullyQualifiedClassName
    ]
)

private[testProvider] final case class TestFileMetadata(
    entries: List[TestEntry],
    textDocument: Option[TextDocument]
)

private[testProvider] final case class TestEntry(
    buildTarget: BuildTarget,
    path: AbsolutePath,
    suiteName: FullyQualifiedName,
    symbol: mtags.Symbol,
    testClass: AddTestSuite
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
    mutable.Map[
      BuildTarget,
      mutable.Map[FullyQualifiedName, TestEntry]
    ]()
  private val fileToMetadata = mutable.Map[AbsolutePath, TestFileMetadata]()

  def suites: Iterable[(BuildTarget, Iterable[TestEntry])] =
    cachedTestSuites.mapValues(_.values).toIterable

  def put(
      entry: TestEntry,
      textDocument: TextDocument
  ): Unit = {
    cachedTestSuites.get(entry.buildTarget) match {
      case Some(suites) =>
        suites.put(entry.suiteName, entry)
      case None =>
        val suites = mutable.Map(entry.suiteName -> entry)
        cachedTestSuites.put(entry.buildTarget, suites)
    }

    fileToMetadata.get(entry.path) match {
      case Some(metadata) =>
        val updated = metadata.copy(
          entries = entry :: metadata.entries,
          textDocument = Some(textDocument)
        )
        fileToMetadata.put(entry.path, updated)
      case None =>
        val metadata = TestFileMetadata(List(entry), Some(textDocument))
        fileToMetadata.put(entry.path, metadata)
    }
  }

  def updateDoc(path: AbsolutePath, doc: TextDocument): Unit =
    fileToMetadata.get(path).foreach { metadata =>
      val updated = metadata.copy(textDocument = Some(doc))
      fileToMetadata.update(path, updated)
    }

  def contains(path: AbsolutePath): Boolean = fileToMetadata.contains(path)

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
          metadata.entries.filter(_.testClass.fullyQualifiedName != suiteName)
        if (filtered.isEmpty)
          fileToMetadata.remove(entry.path)
        else
          fileToMetadata.put(entry.path, metadata.copy(entries = filtered))
      }
      entry
    }
  }
}
