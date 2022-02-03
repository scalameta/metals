package scala.meta.internal.metals.testProvider

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbFeatureProvider
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
import ch.epfl.scala.bsp4j.ScalaPlatform
import org.eclipse.{lsp4j => l}

final class TestSuitesProvider(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    symbolIndex: GlobalSymbolIndex,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    clientConfig: ClientConfiguration,
    client: MetalsLanguageClient
)(implicit ec: ExecutionContext)
    extends SemanticdbFeatureProvider {

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
   * 2. test suite's file can be opened and test cases are visible
   */
  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = {
    if (isEnabled && index.hasTestCasesGranularity(file)) {
      if (docs.documents.nonEmpty) {
        val doc = docs.documents.head
        refreshTestCases(file, Some(doc))
      }
    }
  }

  override def onDelete(file: AbsolutePath): Unit = {
    val removed = index.remove(file)
    val removeEvents = removed
      .groupBy(_.buildTarget)
      .map { case (buildTarget, entries) =>
        BuildTargetUpdate(buildTarget, entries.map(_.testClass.asRemove))
      }
      .toList

    if (removeEvents.nonEmpty) {
      updateClient(removeEvents: _*)
    }
  }

  override def reset(): Unit = ()

  /**
   * Check if opened file contains test suite and update test cases if yes.
   */
  def didOpen(file: AbsolutePath): Future[Unit] =
    if (isEnabled && index.contains(file)) Future(refreshTestCases(file, None))
    else Future.unit

  /**
   * Discover tests:
   * - for a workspace if path isn't defined
   * - for a given file if path is defined
   */
  def discoverTests(
      path: Option[AbsolutePath]
  ): java.util.List[BuildTargetUpdate] = {
    val updates = path match {
      case Some(path0) => getTestCases(path0, None)
      case None => getTestSuites()
    }
    updates.asJava
  }

  private def updateClient(updates: BuildTargetUpdate*): Unit = {
    client.metalsExecuteClientCommand(
      ClientCommands.UpdateTestExplorer.toExecuteCommandParams(
        updates: _*
      )
    )
  }

  /**
   * Retrieves all cached test suites. Useful for tests.
   */
  private def getTestSuites(): Seq[BuildTargetUpdate] = {
    index.allSuites.map { case (buildTarget, entries) =>
      BuildTargetUpdate(buildTarget, entries.map(_.testClass).toSeq)
    }.toSeq
  }

  /**
   * Retrieve test cases for a given file. Even an empty list is being sent
   * because it can mean that all testcases were deleted.
   */
  private def getTestCases(
      path: AbsolutePath,
      doc: Option[TextDocument]
  ): List[BuildTargetUpdate] = {
    val buildTargetUpdates =
      for {
        metadata <- index.getMetadata(path).toList
        events = findTestCases(path, doc)
        buildTarget <- metadata.entries.map(_.buildTarget)
      } yield {
        BuildTargetUpdate(buildTarget, events)
      }
    buildTargetUpdates
  }

  private def refreshTestCases(
      path: AbsolutePath,
      doc: Option[TextDocument]
  ): Unit = {
    val buildTargetUpdates = getTestCases(path, doc)
    if (buildTargetUpdates.nonEmpty) {
      client.metalsExecuteClientCommand(
        ClientCommands.UpdateTestExplorer.toExecuteCommandParams(
          buildTargetUpdates: _*
        )
      )
    }
  }

  /**
   * Searches for test cases for a given path.
   */
  private def findTestCases(
      path: AbsolutePath,
      textDocument: Option[TextDocument]
  ): Seq[AddTestCases] =
    for {
      metadata <- index.getMetadata(path).toSeq
      metadataEntry <- metadata.entries
      testEntry <- index.getTestEntry(
        metadataEntry.buildTarget,
        metadataEntry.suiteName
      )
      // if text document isn't defined try to fetch it from semanticdbs
      doc <- textDocument.orElse(fetchTextDocument(testEntry.symbol))
    } yield {
      val testClass = testEntry.testClass
      val testCases = junitTestFinder.findTests(doc, path, testEntry.symbol)

      if (testCases.nonEmpty) {
        index.setHasTestCasesGranularity(path)
      }

      AddTestCases(
        testClass.fullyQualifiedClassName,
        testClass.className,
        testCases.asJava
      )
    }

  private def fetchTextDocument(symbol: mtags.Symbol): Option[TextDocument] = {
    for {
      definition <- symbolIndex.definition(symbol)
      doc <- semanticdbs.textDocument(definition.path).documentIncludingStale
    } yield doc
  }

  /**
   * Find test suites for all build targets in current projects and update caches.
   */
  private def doRefreshTestSuites(): Future[Unit] =
    if (isEnabled) Future {
      val buildTargetList = buildTargets.allBuildTargetIds.toList
        // filter out JS and Native platforms
        .filter(id =>
          buildTargets
            .scalaTarget(id)
            .forall(_.scalaInfo.getPlatform == ScalaPlatform.JVM)
        )
        .flatMap(buildTargets.info)
        .filterNot(_.isSbtBuild)

      val symbolsPerTarget = buildTargetList
        .map { buildTarget =>
          SymbolsPerTarget(
            buildTarget,
            buildTargetClasses.classesOf(buildTarget.getId).testClasses
          )
        }

      val deletedSuites = removeStaleTestSuites(symbolsPerTarget)
      val addedEntries = getTestEntries(symbolsPerTarget)

      val addedTestCases = addedEntries.mapValues {
        _.flatMap { entry =>
          if (buffers.contains(entry.path))
            findTestCases(entry.path, None)
          else Seq.empty
        }
      }

      val addedSuites = addedEntries.mapValues(_.map(_.testClass))

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
  ): Map[BuildTarget, List[TestExplorerEvent]] = {
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
      symbolsPerTarget: List[SymbolsPerTarget]
  ): Map[BuildTarget, List[TestEntry]] = {
    val entries = for {
      SymbolsPerTarget(buildTarget, testSymbols) <- symbolsPerTarget
      cachedSuites = index.getSuites(buildTarget)
      (symbol, fullyQualifiedClassName) <- testSymbols
        .readOnlySnapshot()
        .toList
      // IMPORTANT this check is meant to check for class name, not a symbol
      fullyQualifiedName = FullyQualifiedName(fullyQualifiedClassName)
      if !cachedSuites.contains(fullyQualifiedName)
      entryWithDoc <- {
        val mSymbol = mtags.Symbol(symbol)
        computeTestEntry(
          buildTarget,
          mSymbol,
          fullyQualifiedName
        )
      }
    } yield entryWithDoc
    entries.groupBy(_.buildTarget)
  }

  /**
   * Compute BuildTargetUpdates from added and deleted entries.
   * For added entry check if it's located in currently opened file, if yes try to find test cases for it.
   * Order of events in build target update: delete suite, add suite, add test cases.
   */
  private def getBuildTargetUpdates(
      deletedSuites: Map[BuildTarget, List[TestExplorerEvent]],
      addedSuites: Map[BuildTarget, List[TestExplorerEvent]],
      addedTestCases: Map[BuildTarget, List[TestExplorerEvent]]
  ): Seq[BuildTargetUpdate] = {
    // because events are being prepended list them in reversed order
    // (testcases, add, remove)
    val allEvents =
      addedTestCases.toSeq ++ addedSuites.toSeq ++ deletedSuites.toSeq
    val aggregated =
      allEvents.foldLeft(Map.empty[BuildTarget, List[TestExplorerEvent]]) {
        case (acc, (target, events)) =>
          val prev = acc.getOrElse(target, List.empty)
          acc.updated(target, events ++ prev)
      }

    aggregated.flatMap { case (target, events) =>
      if (events.nonEmpty) Some(BuildTargetUpdate(target, events))
      else None
    }.toSeq
  }

  private def getSymbolLocation(
      symbol: mtags.Symbol
  ): Option[(mtags.SymbolDefinition, l.Location)] = {
    for {
      // check if definition symbol is equal to queried symbol
      definition <- symbolIndex
        .definition(symbol)
        .filter(_.definitionSymbol == symbol)
      location <- {
        definition.range
          .map { range =>
            new l.Location(
              definition.path.toURI.toString,
              range.toLSP
            )
          }
          .orElse {
            semanticdbs
              .textDocument(definition.path)
              .documentIncludingStale
              .flatMap(
                _.toLocation(definition.path.toURI.toString, symbol.value)
              )
          }
      }
    } yield (definition, location)
  }

  private def computeTestEntry(
      buildTarget: BuildTarget,
      symbol: mtags.Symbol,
      fullyQualifiedName: FullyQualifiedName
  ): Option[TestEntry] = {
    val className = fullyQualifiedName.value.split('.').last
    val entryOpt = for {
      (definition, location) <- getSymbolLocation(symbol)
    } yield {
      val testClass = AddTestSuite(
        fullyQualifiedName.value,
        className,
        symbol.value,
        location,
        true
      )

      val entry = TestEntry(
        buildTarget,
        definition.path,
        fullyQualifiedName,
        symbol,
        testClass
      )

      index.put(entry)
      entry
    }

    entryOpt
  }

}
