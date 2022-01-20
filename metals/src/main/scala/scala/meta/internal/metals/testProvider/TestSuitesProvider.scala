package scala.meta.internal.metals.testProvider

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.metals.testProvider.frameworks.JunitTestFinder
import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget

final class TestSuitesProvider(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    symbolIndex: GlobalSymbolIndex,
    semanticdbs: Semanticdbs,
    openedFiles: () => Iterable[AbsolutePath],
    clientConfig: ClientConfiguration,
    client: MetalsLanguageClient
)(implicit ec: ExecutionContext) {

  private val index = new TestSuitesIndex
  private val junitTestFinder = new JunitTestFinder

  private def isEnabled = clientConfig.isTestExplorerProvider()

  val refreshTestSuites: BatchedFunction[Unit, Unit] =
    BatchedFunction.fromFuture(_ => doRefreshTestSuites())

  /**
   * Update test cases for given path.
   * Only consider 'relevant' files which have had already discovered test suite.
   * Metals have to update them because they can be visible to the user via:
   * 1. Test Explorer view can be opened and tree view is visible
   * 2. test suite's file can be opened
   */
  def onChange(docs: TextDocuments, file: AbsolutePath): Unit = {
    if (isEnabled && index.contains(file)) {
      if (docs.documents.nonEmpty) {
        val doc = docs.documents.head
        index.updateDoc(file, doc)
      }
      refreshTestCases(file)
    }
  }

  /**
   * Check if opened file contains test suite and update test cases if yes.
   */
  def didOpen(file: AbsolutePath): Future[Unit] =
    if (isEnabled && index.contains(file)) Future(refreshTestCases(file))
    else Future.unit

  /**
   * Retrieves all cached test suites. Useful for tests.
   */
  def getTestSuites(): java.util.List[BuildTargetUpdate] = {
    index.suites
      .map { case (buildTarget, entries) =>
        BuildTargetUpdate(buildTarget, entries.map(_.testClass).toSeq)
      }
      .toSeq
      .asJava
  }

  /**
   * Retrieve test cases for a given file. Even an empty list
   * because it can mean that all testcases were deleted.
   */
  def getTestCases(path: AbsolutePath): Seq[BuildTargetUpdate] = {
    val buildTargetUpdateOpt =
      for {
        metadata <- index.getMetadata(path)
        buildTarget <- metadata.entries.headOption.map(_.buildTarget)
      } yield {
        val events = findTestCases(path)
        BuildTargetUpdate(buildTarget, events)
      }
    buildTargetUpdateOpt.toSeq
  }

  private def refreshTestCases(path: AbsolutePath): Unit = {
    val buildTargetUpdateOpt = getTestCases(path)
    buildTargetUpdateOpt.foreach { update =>
      client.metalsExecuteClientCommand(
        ClientCommands.UpdateTestExplorer.toExecuteCommandParams(
          update
        )
      )
    }
  }

  /**
   * Searches for test cases for a given path.
   */
  private def findTestCases(
      path: AbsolutePath,
      symbol: Option[mtags.Symbol] = None
  ): Seq[AddTestCases] =
    for {
      metadata <- index.getMetadata(path).toSeq
      doc <- metadata.textDocument.toSeq
      metadataEntry <- metadata.entries
      testEntry <- index.getTestEntry(
        metadataEntry.buildTarget,
        metadataEntry.suiteName
      )
      if symbol.forall(_ == testEntry.symbol)
    } yield {
      val testClass = testEntry.testClass
      val testCases = junitTestFinder.findTests(doc, path, testEntry.symbol)
      AddTestCases(
        testClass.fullyQualifiedName,
        testClass.clsName,
        testCases.asJava
      )
    }

  /**
   * Find test suites for all build targets in current projects and update caches.
   */
  private def doRefreshTestSuites(): Future[Unit] =
    if (isEnabled) Future {
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

      val deletedSuites = removeStaleTestSuites(symbolsPerTarget)
      val addedEntries = getTestEntries(symbolsPerTarget)

      // update cache
      addedEntries.values.foreach {
        _.foreach { case (entry, doc) =>
          index.put(entry, doc)
        }
      }

      val currentlyOpened = openedFiles().toSet
      val addedTestCases = addedEntries.mapValues {
        _.flatMap { case (entry, _) =>
          if (currentlyOpened.contains(entry.path)) findTestCases(entry.path)
          else Seq.empty
        }
      }

      val addedSuites = addedEntries.mapValues(_.map { case (entry, _) =>
        entry.testClass
      })

      val buildTargetUpdates =
        getBuildTargetUpdates(deletedSuites, addedSuites, addedTestCases)

      if (buildTargetUpdates.nonEmpty) {
        client.metalsExecuteClientCommand(
          ClientCommands.UpdateTestExplorer.toExecuteCommandParams(
            buildTargetUpdates: _*
          )
        )
      }
    }
    else Future.successful(())

  /**
   * BSP server return fully qualified names of all test suites per build target
   * Remove cached entries which were deleted (not returned by BSP)
   */
  private def removeStaleTestSuites(
      symbolsPerTargets: List[SymbolsPerTarget]
  ): Map[BuildTarget, Seq[TestExplorerEvent]] = {
    // when test suite is deleted it has to be removed from cache
    symbolsPerTargets.map { case SymbolsPerTarget(buildTarget, testSymbols) =>
      val fromBSP = testSymbols.values.toSet.map(FullyQualifiedName(_))
      val cached = index.getSuiteNames(buildTarget)
      val diff = (cached -- fromBSP)
      val removed = diff.foldLeft(List.empty[TestExplorerEvent]) {
        case (deleted, unusedClassName) =>
          index.remove(buildTarget, unusedClassName) match {
            case Some(entry) => entry.testClass.asRemove :: deleted
            case None => deleted
          }
      }
      (buildTarget, removed)
    }.toMap
  }

  /**
   * Returns discovered test entries per build target.
   */
  private def getTestEntries(
      symbolsPerTarget: Seq[SymbolsPerTarget]
  ): Map[BuildTarget, Seq[(TestEntry, TextDocument)]] = {
    val entries = for {
      SymbolsPerTarget(buildTarget, testSymbols) <- symbolsPerTarget
      cachedSuites = index.getSuites(buildTarget)
      (symbol, fullyQualifiedClassName) <- testSymbols
        .readOnlySnapshot()
        .toList
      // IMPORTANT this check is meant to check for class name, not a symbol
      fullyQualifiedName = FullyQualifiedName(fullyQualifiedClassName)
      if !cachedSuites.contains(fullyQualifiedName)
      entry <- {
        val mSymbol = mtags.Symbol(symbol)
        computeTestEntry(
          buildTarget,
          mSymbol,
          fullyQualifiedName
        )
      }
    } yield entry
    entries.groupBy(_._1.buildTarget)
  }

  /**
   * Compute BuildTargetUpdates from added and deleted entries.
   * For added entry check if it's located in currently opened file, if yes try to find test cases for it.
   */
  private def getBuildTargetUpdates(
      deletedSuites: Map[BuildTarget, Seq[TestExplorerEvent]],
      addedSuites: Map[BuildTarget, Seq[TestExplorerEvent]],
      addedTestCases: Map[BuildTarget, Seq[TestExplorerEvent]]
  ): Seq[BuildTargetUpdate] = {

    val buildTargetUpdates =
      (deletedSuites.keySet ++ addedSuites.keySet ++ addedTestCases.keySet)
        .map { buildTarget =>
          val deleted = deletedSuites.get(buildTarget).getOrElse(Seq.empty)
          val added = addedSuites.get(buildTarget).getOrElse(Seq.empty)
          val added0 = addedTestCases.get(buildTarget).getOrElse(Seq.empty)
          BuildTargetUpdate(buildTarget, deleted ++ added ++ added0)
        }
        .filterNot(_.events.isEmpty())
        .toSeq
    buildTargetUpdates
  }

  private def computeTestEntry(
      buildTarget: BuildTarget,
      symbol: mtags.Symbol,
      fullyQualifiedName: FullyQualifiedName
  ): Option[(TestEntry, TextDocument)] = {
    // fullyQualifiedClassName always contains at least one element - class name
    val fullyQualifiedClassName = fullyQualifiedName.value.split('.').toSeq
    val entryWithDocumentOpt =
      for {
        definition <- symbolIndex.definition(mtags.Symbol(symbol.value))
        doc <- semanticdbs.textDocument(definition.path).documentIncludingStale
        location <- doc.toLocation(definition.path.toURI.toString, symbol.value)
        className <- fullyQualifiedClassName.takeRight(1).headOption
      } yield {
        val canResolveChildren = doc.occurrences.exists(
          _.symbol == JunitTestFinder.junitAnnotationSymbol
        )

        val testClass = AddTestSuite(
          fullyQualifiedName,
          ClassName(className),
          symbol,
          location,
          canResolveChildren
        )

        val entry = TestEntry(
          buildTarget,
          definition.path,
          fullyQualifiedName,
          symbol,
          testClass
        )

        (entry, doc)
      }

    entryWithDocumentOpt
  }

}
